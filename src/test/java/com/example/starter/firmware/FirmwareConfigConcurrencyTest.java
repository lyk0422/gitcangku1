package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.MigrationRuleInput;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateDeviceConfigRequest;
import com.example.starter.firmware.domain.MigrationRuleType;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置迁移并发边界测试：真实并发打到 H2 事务与唯一约束上，
 * 验证拉取、本地配置修改、回执共享设备状态的一致提交顺序。
 */
@SpringBootTest
class FirmwareConfigConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

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
        jdbc.update("DELETE FROM device_config");
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
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
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

    private long createMigrationRelease(String requestId) {
        return releaseService.create(new CreateReleaseRequest(requestId, "m1", "1.0.0", "2.0.0", 100, null,
                null, List.of(new MigrationRuleInput(MigrationRuleType.RENAME, "a", "b", null),
                        new MigrationRuleInput(MigrationRuleType.MULTIPLY, "b", null, 2)))).releaseId();
    }

    @Test
    void 并发拉取_同一冻结快照且任务唯一() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1, Map.of("a", 3)));
        long releaseId = createMigrationRelease("req-r");

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
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
            assertThat(response.task().configBefore()).isEqualTo(Map.of("a", 3));
            assertThat(response.task().configAfter()).isEqualTo(Map.of("b", 6));
            assertThat(response.task().configVersionBefore()).isEqualTo(1);
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
    }

    @Test
    void 并发本地修改与拉取_一致提交顺序() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1,
                    Map.of("a", 3)));
            long releaseId = releaseService.create(new CreateReleaseRequest("req-r" + round, model,
                    "1.0.0", "2.0.0", 100, null, null,
                    List.of(new MigrationRuleInput(MigrationRuleType.RENAME, "a", "b", null)))).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    () -> taskService.pull(deviceId, "req-p" + seq),
                    () -> deviceService.updateConfig(deviceId,
                            new UpdateDeviceConfigRequest("req-c" + seq, "op-1", 1, Map.of("x", 9)))));

            Object pullResult = results.get(0);
            Object configResult = results.get(1);
            assertThat(pullResult).isNotInstanceOf(Exception.class);
            PullResponse pull = (PullResponse) pullResult;
            if (configResult instanceof ApiException ae) {
                // 拉取先提交：任务已 PENDING，本地修改 409，快照基于修改前配置
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("TASK_IN_PROGRESS");
                assertThat(pull.task().configBefore()).isEqualTo(Map.of("a", 3));
                assertThat(deviceService.get(deviceId).configVersion()).isEqualTo(1);
            } else {
                // 本地修改先提交：配置版本推进，任务快照基于修改后配置
                assertThat(deviceService.get(deviceId).configVersion()).isEqualTo(2);
                assertThat(pull.task().configBefore()).isEqualTo(Map.of("a", 3, "x", 9));
                assertThat(pull.task().configAfter()).isEqualTo(Map.of("b", 3, "x", 9));
            }
            assertThat(taskRepository.countByReleaseAndDevice(releaseId, deviceId)).isEqualTo(1);
        }
    }

    @Test
    void 并发成功回执与本地修改_回执应用快照不被干扰() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1,
                    Map.of("a", 3)));
            createMigrationReleaseFor(model, "req-r" + round);
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    () -> taskService.receipt(taskId, new ReceiptRequest("req-s" + seq, ReceiptResult.SUCCESS)),
                    () -> deviceService.updateConfig(deviceId,
                            new UpdateDeviceConfigRequest("req-c" + seq, "op-1", 2, Map.of("x", 9)))));

            Object receiptResult = results.get(0);
            Object configResult = results.get(1);
            assertThat(receiptResult).as("回执对 PENDING 任务始终成功").isNotInstanceOf(Exception.class);
            var device = deviceService.get(deviceId);
            assertThat(device.currentVersion()).isEqualTo("2.0.0");
            assertThat(device.config()).containsEntry("b", 6).doesNotContainKey("a");
            if (configResult instanceof ApiException ae) {
                // 本地修改先拿到设备锁：配置版本仍为 1，期望版本 2 不符，409；回执随后应用快照
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("CONFIG_VERSION_CONFLICT");
                assertThat(device.configVersion()).isEqualTo(2);
                assertThat(device.config()).doesNotContainKey("x");
            } else {
                // 回执先提交：本地修改基于迁移后配置成功
                assertThat(device.configVersion()).isEqualTo(3);
                assertThat(device.config()).containsEntry("x", 9);
            }
        }
    }

    @Test
    void 并发同requestId本地修改_重放一致且只应用一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1, Map.of("a", 3)));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.updateConfig("d1",
                    new UpdateDeviceConfigRequest("req-same", "op-1", 1, Map.of("x", 5))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        // 配置版本只推进一次，证明重放而非重复执行
        var device = deviceService.get("d1");
        assertThat(device.configVersion()).isEqualTo(2);
        assertThat(device.config()).isEqualTo(Map.of("a", 3, "x", 5));
    }

    private void createMigrationReleaseFor(String model, String requestId) {
        releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 100, null, null,
                List.of(new MigrationRuleInput(MigrationRuleType.RENAME, "a", "b", null),
                        new MigrationRuleInput(MigrationRuleType.MULTIPLY, "b", null, 2))));
    }
}
