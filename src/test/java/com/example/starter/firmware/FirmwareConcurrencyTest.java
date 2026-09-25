package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发与幂等边界测试：真实并发打到 H2 事务与唯一约束上，验证一致提交顺序。
 */
@SpringBootTest
class FirmwareConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ReleaseRepository releaseRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM region_wait_record");
        jdbc.update("DELETE FROM region_throttle_event");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 闩锁对齐后并发执行，收集成功结果与异常。
     */
    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void 并发拉取_同设备同发布单最多一条任务() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5, "cn-north"));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100, null))
                .releaseId();

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
    }

    @Test
    void 并发创建_同型号至多一张ACTIVE发布单() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> releaseService.create(
                    new CreateReleaseRequest("req-c" + seq, "m1", "1.0.0", "2.0.0", 10, null)).releaseId());
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_order WHERE status = 'ACTIVE'", Long.class);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void 并发拉取与取消_最终状态一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 5, "cn-north"));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0", 100, null)).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.CANCELLED);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status = 'PENDING'",
                    Long.class, releaseId);
            assertThat(pending).as("取消后不得残留 PENDING 任务（第%d轮）", round).isZero();
        }
    }

    @Test
    void 并发成功回执与取消_一致提交顺序且已成功不回滚() throws Exception {
        int successWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 5, "cn-north"));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0", 100, null)).releaseId();
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-s" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));

            Object receiptResult = results.get(0);
            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            String deviceVersion = deviceService.get(deviceId).currentVersion();
            if (finalStatus == TaskStatus.SUCCESS) {
                successWins++;
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
                assertThat(deviceVersion).isEqualTo("2.0.0");
            } else {
                cancelWins++;
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(deviceVersion).isEqualTo("1.0.0");
            }
            // 取消后任务不再处于 PENDING，且已成功设备不回滚
            assertThat(finalStatus).isNotEqualTo(TaskStatus.PENDING);
        }
        assertThat(successWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 并发同requestId_重放一致且副作用只发生一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5, "cn-north"));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 10, null))
                .releaseId();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> releaseService.expand(releaseId,
                    new com.example.starter.firmware.api.ExpandReleaseRequest("req-same", 1, 60)));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        // 版本只加一次，证明重放而非重复执行
        assertThat(releaseService.findOrder(releaseId).version()).isEqualTo(2);
        assertThat(releaseService.findOrder(releaseId).ratio()).isEqualTo(60);
    }
}
