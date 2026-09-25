package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateFreezeRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.FreezeOrderView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.FreezeService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 冻结令并发与幂等边界测试：冻结、撤销、发布启动、拉取、回执按提交顺序裁决，
 * 真实并发打到 H2 事务与行锁上。
 */
@SpringBootTest
class FirmwareFreezeConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FreezeService freezeService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM freeze_exception_record");
        jdbc.update("DELETE FROM freeze_order");
        jdbc.update("DELETE FROM freeze_approver");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

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

    private static CreateFreezeRequest activeFreeze(String freezeKey, String model) {
        return new CreateFreezeRequest(freezeKey, List.of(model), List.of(),
                Instant.now().minusSeconds(3600).toString(), Instant.now().plusSeconds(3600).toString(), null);
    }

    @Test
    void 并发冻结创建与拉取_按提交顺序裁决不留半成品() throws Exception {
        int pullFirst = 0;
        int freezeFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 5));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0", 100)).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> freezeService.create(activeFreeze("fk-" + seq, model))));

            Object pullResult = results.get(0);
            assertThat(results.get(1)).as("冻结创建始终成功").isInstanceOf(FreezeOrderView.class);
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
            if (pullResult instanceof ApiException ae) {
                // 冻结先提交：拉取 422，不创建任务
                freezeFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("RELEASE_FROZEN");
                assertThat(taskCount).isZero();
            } else {
                // 拉取先提交：任务创建后被同一冻结令冻结
                pullFirst++;
                assertThat(((PullResponse) pullResult).task()).isNotNull();
                assertThat(taskCount).isEqualTo(1);
                assertThat(taskRepository.findByRelease(releaseId, null).get(0).status())
                        .isEqualTo(TaskStatus.RELEASE_FROZEN);
            }
        }
        assertThat(pullFirst + freezeFirst).isEqualTo(10);
    }

    @Test
    void 并发发布启动与冻结创建_按提交顺序裁决() throws Exception {
        int releaseFirst = 0;
        int freezeFirst = 0;
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> releaseService.create(
                            new CreateReleaseRequest("req-r" + seq, model, "1.0.0", "2.0.0", 100)),
                    (Callable<Object>) () -> freezeService.create(activeFreeze("fk-" + seq, model))));

            Object releaseResult = results.get(0);
            assertThat(results.get(1)).isInstanceOf(FreezeOrderView.class);
            Long releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_order WHERE model = ?", Long.class, model);
            if (releaseResult instanceof ApiException ae) {
                freezeFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("RELEASE_FROZEN");
                assertThat(releaseCount).isZero();
            } else {
                releaseFirst++;
                assertThat(releaseCount).isEqualTo(1);
            }
        }
        assertThat(releaseFirst + freezeFirst).isEqualTo(10);
    }

    @Test
    void 并发同freezeKey创建_重放一致且只创建一条() throws Exception {
        CreateFreezeRequest sameRequest = activeFreeze("fk-same", "m1");
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> freezeService.create(sameRequest));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> freezeIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(FreezeOrderView.class);
            freezeIds.add(((FreezeOrderView) result).freezeId());
        }
        assertThat(freezeIds).allMatch(id -> id.equals(freezeIds.get(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发撤销与回执_按提交顺序产生唯一合法状态() throws Exception {
        int revokeFirst = 0;
        int receiptFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 5));
            releaseService.create(new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0", 100));
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            long freezeId = freezeService.create(activeFreeze("fk-" + round, model)).freezeId();
            assertThat(taskRepository.findById(taskId).orElseThrow().status())
                    .isEqualTo(TaskStatus.RELEASE_FROZEN);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> freezeService.revoke(freezeId, "req-rv" + seq)));

            Object receiptResult = results.get(0);
            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            if (receiptResult instanceof ApiException ae) {
                // 回执先裁决：冻结中 409，随后撤销解冻回 PENDING
                receiptFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("TASK_FROZEN");
                assertThat(finalStatus).isEqualTo(TaskStatus.PENDING);
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
            } else {
                // 撤销先提交：任务解冻后回执成功
                revokeFirst++;
                assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS);
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
            }
            assertThat(freezeService.get(freezeId).status()).isEqualTo("REVOKED");
        }
        assertThat(revokeFirst + receiptFirst).isEqualTo(10);
    }
}
