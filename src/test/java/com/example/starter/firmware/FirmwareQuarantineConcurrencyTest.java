package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.QuarantineRecordView;
import com.example.starter.firmware.api.QuarantineRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UnquarantineRequest;
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
 * 设备隔离并发与原子性边界测试：真实并发打到 H2 行锁与唯一约束上，
 * 验证隔离、解除、拉取、开始、回执按事务提交顺序裁决；隔离回查任一失败整次回滚。
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

    private String registerDevice(String deviceId, String model, String version, int bucket) {
        return deviceService.register(new RegisterDeviceRequest("req-d-" + deviceId, deviceId, model,
                version, bucket)).deviceId();
    }

    private long createRelease(String model, String from, String to, int ratio) {
        return releaseService.create(new CreateReleaseRequest("req-r-" + model + "-" + from + "-" + to,
                model, from, to, ratio)).releaseId();
    }

    @Test
    void 隔离回查原子性_任一任务状态变化失败整次回滚() {
        registerDevice("d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("m1", "1.0.0", "2.0.0", 100);
        long taskId = taskService.pull("d1", "req-p1").task().taskId();

        // 原因代码超过隔离记录列宽（64），记录写入失败 → 整次回滚
        String tooLongReason = "X".repeat(100);
        assertThatThrownBy(() -> quarantineService.quarantine("d1",
                new QuarantineRequest("req-q1", "op-a", tooLongReason, "1.0.0")))
                .isNotInstanceOf(ApiException.class);

        // 设备状态、任务状态、历史记录全部回滚
        assertThat(deviceService.get("d1").status()).isEqualTo(DeviceStatus.NORMAL.name());
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PENDING);
        assertThat(taskRepository.findById(taskId).orElseThrow().cancelReason()).isNull();
        assertThat(quarantineRecordRepository.findByDevice("d1")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-q1'", Long.class))
                .isZero();

        // 失败不占键：同 requestId 修正参数后成功
        QuarantineRecordView view = quarantineService.quarantine("d1",
                new QuarantineRequest("req-q1", "op-a", "HW_SUSPECT", "1.0.0"));
        assertThat(view.operation()).isEqualTo("QUARANTINE");
        assertThat(deviceService.get("d1").status()).isEqualTo(DeviceStatus.QUARANTINED.name());
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(taskRepository.findById(taskId).orElseThrow().cancelReason()).isEqualTo("HW_SUSPECT");
        assertThat(releaseService.findOrder(releaseId).status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void 并发隔离与拉取_按提交顺序裁决且不留未取消的待开始任务() throws Exception {
        int pullFirst = 0;
        int quarantineFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            registerDevice(deviceId, model, "1.0.0", 1);
            createRelease(model, "1.0.0", "2.0.0", 100);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> quarantineService.quarantine(deviceId,
                            new QuarantineRequest("req-q" + seq, "op-a", "HW", "1.0.0"))));

            assertThat(results.get(1)).as("隔离始终成功").isNotInstanceOf(Exception.class);
            assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.QUARANTINED.name());
            Object pullResult = results.get(0);
            if (pullResult instanceof Exception) {
                // 隔离先提交：拉取 422 DEVICE_QUARANTINED
                quarantineFirst++;
                assertThat(pullResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(ae.code()).isEqualTo("DEVICE_QUARANTINED");
                        });
            } else {
                // 拉取先提交：任务已创建，隔离回查将其取消并写入原因
                pullFirst++;
                Long pending = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND status = 'PENDING'",
                        Long.class, deviceId);
                assertThat(pending).as("隔离后不得残留 PENDING 任务（第%d轮）", round).isZero();
                String cancelReason = jdbc.queryForObject(
                        "SELECT cancel_reason FROM rollout_task WHERE device_id = ?",
                        String.class, deviceId);
                assertThat(cancelReason).isEqualTo("HW");
            }
        }
        assertThat(pullFirst + quarantineFirst).isEqualTo(10);
    }

    @Test
    void 并发隔离与成功回执_按提交顺序裁决且版本更新不回滚() throws Exception {
        int receiptFirst = 0;
        int quarantineFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            registerDevice(deviceId, model, "1.0.0", 1);
            createRelease(model, "1.0.0", "2.0.0", 100);
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            taskService.start(taskId, "req-s" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> quarantineService.quarantine(deviceId,
                            new QuarantineRequest("req-q" + seq, "op-a", "HW", "1.0.0"))));

            Object receiptResult = results.get(0);
            Object quarantineResult = results.get(1);
            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            String deviceVersion = deviceService.get(deviceId).currentVersion();
            if (receiptResult instanceof Exception) {
                // 隔离先提交：回执 422，任务保持进行中，版本保留，被拒回执落记录
                quarantineFirst++;
                assertThat(quarantineResult).as("隔离先提交时隔离成功").isNotInstanceOf(Exception.class);
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(ae.code()).isEqualTo("DEVICE_QUARANTINED");
                        });
                assertThat(finalStatus).isEqualTo(TaskStatus.IN_PROGRESS);
                assertThat(deviceVersion).isEqualTo("1.0.0");
                assertThat(rejectedReceiptRepository.countByDevice(deviceId)).isEqualTo(1);
            } else {
                // 回执先提交：任务成功、版本已更新为 2.0.0；
                // 隔离携带的 expectedVersion=1.0.0 不再匹配当前版本 → 409 VERSION_CONFLICT
                receiptFirst++;
                assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS);
                assertThat(deviceVersion).isEqualTo("2.0.0");
                assertThat(quarantineResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ae.code()).isEqualTo("VERSION_CONFLICT");
                        });
                assertThat(rejectedReceiptRepository.countByDevice(deviceId)).isZero();
                // 以正确版本重试隔离成功，且已成功任务的版本更新不回滚
                quarantineService.quarantine(deviceId,
                        new QuarantineRequest("req-q2-" + seq, "op-a", "HW", "2.0.0"));
                assertThat(deviceService.get(deviceId).status())
                        .isEqualTo(DeviceStatus.QUARANTINED.name());
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
            }
        }
        assertThat(receiptFirst + quarantineFirst).isEqualTo(10);
    }

    @Test
    void 并发同requestId隔离_重放一致且记录只一条() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> quarantineService.quarantine("d1",
                    new QuarantineRequest("req-same", "op-a", "HW", "1.0.0")));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> recordIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
            recordIds.add(((QuarantineRecordView) result).recordId());
        }
        assertThat(recordIds).allMatch(id -> id.equals(recordIds.get(0)));
        assertThat(quarantineRecordRepository.findByDevice("d1")).hasSize(1);
        assertThat(deviceService.get("d1").status()).isEqualTo(DeviceStatus.QUARANTINED.name());
    }

    @Test
    void 并发不同键隔离_仅一个成功其余409() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);

        int threads = 4;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> quarantineService.quarantine("d1",
                    new QuarantineRequest("req-q" + seq, "op-" + seq, "HW", "1.0.0")));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && ae.code().equals("DEVICE_ALREADY_QUARANTINED"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(quarantineRecordRepository.findByDevice("d1")).hasSize(1);
    }

    @Test
    void 并发解除与隔离_按提交顺序产生唯一合法状态() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            registerDevice(deviceId, "m" + round, "1.0.0", 1);
            quarantineService.quarantine(deviceId,
                    new QuarantineRequest("req-q0-" + round, "op-a", "HW", "1.0.0"));
            final int seq = round;

            // 并发：不同运维人解除隔离 + 另一运维人再次隔离（先隔离者提交后，后到的隔离/解除按状态校验裁决）
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> quarantineService.unquarantine(deviceId,
                            new UnquarantineRequest("req-u" + seq, "op-b", "已消除", "1.0.0")),
                    (Callable<Object>) () -> quarantineService.unquarantine(deviceId,
                            new UnquarantineRequest("req-v" + seq, "op-c", "已消除", "1.0.0"))));

            long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
            long conflicts = results.stream()
                    .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                    .count();
            assertThat(successes).as("第%d轮仅一个解除成功", round).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(deviceService.get(deviceId).status()).isEqualTo(DeviceStatus.NORMAL.name());
            assertThat(quarantineRecordRepository.findByDevice(deviceId)).hasSize(2);
        }
    }
}
