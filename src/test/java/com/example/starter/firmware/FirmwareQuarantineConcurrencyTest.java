package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.QuarantineRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ReleaseQuarantineRequest;
import com.example.starter.firmware.domain.DeviceStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.QuarantineRecordRepository;
import com.example.starter.firmware.repo.RejectedReceiptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.QuarantineService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 隔离并发与原子性边界测试（真实 H2 事务）：回查原子性、隔离/解除/拉取/开始/回执/发布启动
 * 按事务提交顺序裁决、同 isolationKey 并发重放。
 */
@SpringBootTest
class FirmwareQuarantineConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private QuarantineService quarantineService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private QuarantineRecordRepository quarantineRecordRepository;

    @Autowired
    private RejectedReceiptRepository rejectedReceiptRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rejected_receipt");
        jdbc.update("DELETE FROM task_cancel_reason");
        jdbc.update("DELETE FROM device_quarantine_record");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
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

    private void register(String deviceId, String model, int bucket) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + deviceId, deviceId, model, "1.0.0", bucket));
    }

    private long createRelease(String model) {
        return releaseService.create(new CreateReleaseRequest("req-r-" + model, model, "1.0.0", "2.0.0", 100))
                .releaseId();
    }

    private QuarantineRequest quarantineRequest(String requestId, String operator) {
        return new QuarantineRequest(requestId, operator, "FAULT", "1.0.0");
    }

    @Test
    void 隔离回查原子性_取消原因写入失败整次回滚() {
        register("d1", "m1", 1);
        long releaseId = createRelease("m1");
        long taskId = taskService.pull("d1", "req-p1").task().taskId();

        // 预置冲突的取消原因记录，使隔离事务内写入必然失败
        jdbc.update("INSERT INTO task_cancel_reason"
                        + " (task_id, release_id, device_id, reason_code, detail, operator)"
                        + " VALUES (?, ?, ?, 'SEEDED', '预置冲突记录', 'SEED')",
                taskId, releaseId, "d1");

        assertThatThrownBy(() -> quarantineService.quarantine("d1", quarantineRequest("req-q1", "op1")))
                .isInstanceOf(DataAccessException.class);

        // 整次回滚：设备仍 ACTIVE、任务仍 PENDING、无隔离记录、预置记录不受影响
        assertThat(deviceService.get("d1").status()).isEqualTo(DeviceStatus.ACTIVE.name());
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PENDING);
        assertThat(quarantineRecordRepository.findByDevice("d1")).isEmpty();
        Long reasons = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_cancel_reason WHERE task_id = ?", Long.class, taskId);
        assertThat(reasons).isEqualTo(1);
    }

    @Test
    void 并发隔离与拉取_按提交顺序裁决最终一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            register(deviceId, model, 1);
            long releaseId = createRelease(model);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> quarantineService.quarantine(deviceId,
                            quarantineRequest("req-q" + seq, "op1"))));

            Object pullResult = results.get(0);
            assertThat(results.get(1)).as("隔离对既有设备始终成功").isNotInstanceOf(Exception.class);
            assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.QUARANTINED.name());

            long taskCount = taskRepository.countByReleaseAndDevice(releaseId, deviceId);
            if (pullResult instanceof ApiException ae) {
                // 隔离先提交：拉取 422，无任务
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("DEVICE_QUARANTINED");
                assertThat(taskCount).isZero();
            } else {
                // 拉取先提交：任务被隔离事务取消并写入不可变原因
                assertThat(pullResult).isInstanceOf(PullResponse.class);
                assertThat(taskCount).isEqualTo(1);
                var task = taskRepository.findByReleaseAndDevice(releaseId, deviceId).orElseThrow();
                assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
                Long reasons = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM task_cancel_reason WHERE task_id = ? AND reason_code = 'DEVICE_QUARANTINED'",
                        Long.class, task.id());
                assertThat(reasons).isEqualTo(1);
            }
        }
    }

    @Test
    void 并发隔离与开始_按提交顺序裁决最终一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            register(deviceId, model, 1);
            long releaseId = createRelease(model);
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.start(taskId, "req-s" + seq),
                    (Callable<Object>) () -> quarantineService.quarantine(deviceId,
                            quarantineRequest("req-q" + seq, "op1"))));

            Object startResult = results.get(0);
            assertThat(results.get(1)).isNotInstanceOf(Exception.class);
            assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.QUARANTINED.name());

            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            if (startResult instanceof ApiException ae) {
                // 隔离先提交：任务已取消，开始被门禁拒绝
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
            } else {
                // 开始先提交：STARTED 任务保持进行中
                assertThat(finalStatus).isEqualTo(TaskStatus.STARTED);
            }
            assertThat(taskRepository.countByReleaseAndDevice(releaseId, deviceId)).isEqualTo(1);
        }
    }

    @Test
    void 并发隔离与成功回执_唯一结果且被拒留痕() throws Exception {
        int receiptWins = 0;
        int quarantineWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            register(deviceId, model, 1);
            createRelease(model);
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            taskService.start(taskId, "req-s" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> quarantineService.quarantine(deviceId,
                            quarantineRequest("req-q" + seq, "op1"))));

            Object receiptResult = results.get(0);
            Object quarantineResult = results.get(1);

            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            String version = deviceService.get(deviceId).currentVersion();
            if (receiptResult instanceof ApiException ae) {
                // 隔离先提交：成功回执 422，任务保持 STARTED，版本保留，留痕一条
                quarantineWins++;
                assertThat(quarantineResult).isNotInstanceOf(Exception.class);
                assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.QUARANTINED.name());
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("DEVICE_QUARANTINED");
                assertThat(finalStatus).isEqualTo(TaskStatus.STARTED);
                assertThat(version).isEqualTo("1.0.0");
                assertThat(rejectedReceiptRepository.countByDevice(deviceId)).isEqualTo(1);
            } else {
                // 回执先提交：任务 SUCCESS 且版本推进到 2.0.0，随后的隔离因 expectedVersion 不匹配 409
                receiptWins++;
                assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS);
                assertThat(version).isEqualTo("2.0.0");
                assertThat(quarantineResult).isInstanceOfSatisfying(ApiException.class, qe -> {
                    assertThat(qe.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(qe.code()).isEqualTo("VERSION_CONFLICT");
                });
                assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.ACTIVE.name());
                assertThat(rejectedReceiptRepository.countByDevice(deviceId)).isZero();
            }
        }
        assertThat(receiptWins + quarantineWins).isEqualTo(10);
    }

    @Test
    void 并发同isolationKey隔离_重放一致且记录唯一() throws Exception {
        register("d1", "m1", 1);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> quarantineService.quarantine("d1", quarantineRequest("req-same", "op1")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(deviceService.get("d1").status()).isEqualTo(DeviceStatus.QUARANTINED.name());
        assertThat(quarantineRecordRepository.findByDevice("d1")).hasSize(1);
    }

    @Test
    void 并发同isolationKey解除_重放一致且记录唯一() throws Exception {
        register("d1", "m1", 1);
        quarantineService.quarantine("d1", quarantineRequest("req-q1", "op1"));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> quarantineService.release("d1",
                    new ReleaseQuarantineRequest("req-same", "op2", "FIXED", "1.0.0")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(deviceService.get("d1").status()).isEqualTo(DeviceStatus.ACTIVE.name());
        assertThat(quarantineRecordRepository.findByDevice("d1")).hasSize(2);
    }

    @Test
    void 并发解除与拉取_解除后允许后续拉取() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            register(deviceId, model, 1);
            long releaseId = createRelease(model);
            quarantineService.quarantine(deviceId, quarantineRequest("req-q" + round, "op1"));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> quarantineService.release(deviceId,
                            new ReleaseQuarantineRequest("req-rel" + seq, "op2", "FIXED", "1.0.0")),
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq)));

            Object releaseResult = results.get(0);
            Object pullResult = results.get(1);
            assertThat(releaseResult).as("解除对隔离设备始终成功").isNotInstanceOf(Exception.class);
            assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.ACTIVE.name());

            long taskCount = taskRepository.countByReleaseAndDevice(releaseId, deviceId);
            if (pullResult instanceof ApiException ae) {
                // 拉取先提交：设备仍隔离，422
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(taskCount).isZero();
            } else {
                // 解除先提交：拉取创建新任务
                assertThat(taskCount).isEqualTo(1);
                var task = taskRepository.findByReleaseAndDevice(releaseId, deviceId).orElseThrow();
                assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
            }
        }
    }

    @Test
    void 并发隔离与发布启动_按提交顺序裁决() throws Exception {
        int createWins = 0;
        int quarantineWins = 0;
        for (int round = 0; round < 6; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            register(deviceId, model, 1);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> releaseService.create(
                            new CreateReleaseRequest("req-r" + seq, model, "1.0.0", "2.0.0", 100)),
                    (Callable<Object>) () -> quarantineService.quarantine(deviceId,
                            quarantineRequest("req-q" + seq, "op1"))));

            Object createResult = results.get(0);
            assertThat(results.get(1)).isNotInstanceOf(Exception.class);
            assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.QUARANTINED.name());

            Long activeCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_order WHERE model = ? AND status = 'ACTIVE'",
                    Long.class, model);
            if (createResult instanceof ApiException ae) {
                // 隔离先提交：全部候选隔离，启动 422
                quarantineWins++;
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("ALL_CANDIDATES_QUARANTINED");
                assertThat(activeCount).isZero();
            } else {
                // 启动先提交：发布单已创建，随后设备被隔离
                createWins++;
                assertThat(activeCount).isEqualTo(1);
            }
        }
        assertThat(createWins + quarantineWins).isEqualTo(6);
    }
}
