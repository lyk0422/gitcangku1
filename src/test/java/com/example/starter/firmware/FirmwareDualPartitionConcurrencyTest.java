package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateDualReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.RollbackRecordRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
 * 双分区试启动并发测试（真实 H2 事务，可控时钟）：
 * 健康确认与失败、到期检查竞争只产生一个终态，失败率统计只计一次，回滚只记录一次。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class FirmwareDualPartitionConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private RollbackRecordRepository rollbackRecordRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_rollback_record");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(T0);
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

    /**
     * 造一台设备、一张双分区发布单并进入 TRIAL，返回任务ID。
     */
    private long newTrialTask(String suffix) {
        deviceService.register(new RegisterDeviceRequest("req-d" + suffix, "d" + suffix, "m" + suffix,
                "v1", 1));
        long releaseId = releaseService.createDual(new CreateDualReleaseRequest("req-r" + suffix, "tester",
                "m" + suffix, "v1", "v2", 100, null, null)).releaseId();
        long taskId = taskService.pull("d" + suffix, "req-p" + suffix).task().taskId();
        taskService.receipt(taskId, new ReceiptRequest("req-t" + suffix, ReceiptResult.SUCCESS, "B", "v2", null));
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.TRIAL);
        assertThat(taskRepository.findById(taskId).orElseThrow().releaseId()).isEqualTo(releaseId);
        return taskId;
    }
    @Test
    void 并发健康确认与失败_唯一终态_统计只计一次() throws Exception {
        int committed = 0;
        int rolledBack = 0;
        for (int round = 0; round < 10; round++) {
            String suffix = "r" + round;
            long taskId = newTrialTask(suffix);
            long releaseId = taskRepository.findById(taskId).orElseThrow().releaseId();

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-h" + suffix, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-f" + suffix, ReceiptResult.FAILED))));

            RolloutTask task = taskRepository.findById(taskId).orElseThrow();
            var order = releaseService.findOrder(releaseId);
            assertThat(order.roundSuccess() + order.roundFailed())
                    .as("第%d轮统计只计一次", round).isEqualTo(1);
            if (task.status() == TaskStatus.COMMITTED) {
                committed++;
                assertThat(results.get(0)).isNotInstanceOf(Exception.class);
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(deviceService.get("d" + suffix).currentVersion()).isEqualTo("v2");
                assertThat(deviceService.get("d" + suffix).activePartition()).isEqualTo("B");
                assertThat(rollbackRecordRepository.countByTask(taskId)).isZero();
            } else {
                rolledBack++;
                assertThat(task.status()).isEqualTo(TaskStatus.ROLLED_BACK);
                assertThat(results.get(1)).isNotInstanceOf(Exception.class);
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(deviceService.get("d" + suffix).currentVersion()).isEqualTo("v1");
                assertThat(deviceService.get("d" + suffix).activePartition()).isEqualTo("A");
                assertThat(rollbackRecordRepository.countByTask(taskId)).isEqualTo(1);
            }
        }
        assertThat(committed + rolledBack).isEqualTo(10);
    }

    @Test
    void 并发到期结算与健康确认_只回滚一次_确认全部409() throws Exception {
        long taskId = newTrialTask("x");
        long releaseId = taskRepository.findById(taskId).orElseThrow().releaseId();
        clock.setInstant(T0.plusSeconds(120));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int seq = i;
            tasks.add(() -> taskService.receipt(taskId, new ReceiptRequest("req-h" + seq,
                    ReceiptResult.SUCCESS, null, null, null)));
            tasks.add(() -> taskService.listByRelease(releaseId, null));
        }
        List<Object> results = runConcurrently(tasks);

        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(conflicts).isEqualTo(3);
        assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.ROLLED_BACK);
        assertThat(rollbackRecordRepository.countByTask(taskId)).isEqualTo(1);
        assertThat(releaseService.findOrder(releaseId).roundFailed()).isEqualTo(1);
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isZero();
        assertThat(deviceService.get("dx").currentVersion()).isEqualTo("v1");
    }

    @Test
    void 并发健康确认_全部成功但统计只计一次() throws Exception {
        long taskId = newTrialTask("c");
        long releaseId = taskRepository.findById(taskId).orElseThrow().releaseId();
        clock.setInstant(T0.plusSeconds(30));

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receipt(taskId, new ReceiptRequest("req-h" + seq,
                    ReceiptResult.SUCCESS, null, null, null)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.COMMITTED);
        var order = releaseService.findOrder(releaseId);
        assertThat(order.roundSuccess()).isEqualTo(1);
        assertThat(order.roundFailed()).isZero();
        assertThat(deviceService.get("dc").currentVersion()).isEqualTo("v2");
        assertThat(deviceService.get("dc").activePartition()).isEqualTo("B");
    }

    @Test
    void 并发回滚后重试拉取_只产生一个新任务ID() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "v1", 1));
        long releaseId = releaseService.createDual(new CreateDualReleaseRequest("req-r", "tester",
                "m1", "v1", "v2", 100, null, null)).releaseId();
        long taskId = taskService.pull("d1", "req-p").task().taskId();
        taskService.receipt(taskId, new ReceiptRequest("req-t", ReceiptResult.SUCCESS, "B", "v2", null));
        taskService.receipt(taskId, new ReceiptRequest("req-f", ReceiptResult.FAILED));
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.ROLLED_BACK);

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pp" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNotNull();
            assertThat(response.task().attemptNo()).isEqualTo(2);
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskIds.get(0)).isNotEqualTo(taskId);
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(2);
    }
}