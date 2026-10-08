package com.example.starter.firmware;

import com.example.starter.firmware.api.ConfigModifyRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceConfigView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.MigrationRule;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RuleType;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.ConfigService;
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
 * 配置迁移并发边界测试：拉取、本地配置修改与回执共享设备状态（真实 H2 事务与行锁）。
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
    private ConfigService configService;

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

    private long createReleaseWithRename(String requestId, String model) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 100,
                null, null, List.of(new MigrationRule(RuleType.RENAME, "a", "b", null)))).releaseId();
    }

    @Test
    void 并发拉取与配置修改_共享设备状态且结果一致() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        configService.modify("d1", new ConfigModifyRequest("req-c1", "tester", 1, Map.of("a", 3)));
        long releaseId = createReleaseWithRename("req-r", "m1");

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.pull("d1", "req-p"),
                (Callable<Object>) () -> configService.modify("d1",
                        new ConfigModifyRequest("req-c2", "tester", 2, Map.of("a", 5)))));

        Object pullResult = results.get(0);
        Object modifyResult = results.get(1);
        assertThat(pullResult).isInstanceOf(PullResponse.class);
        PullResponse pull = (PullResponse) pullResult;
        assertThat(pull.task()).isNotNull();

        if (modifyResult instanceof ApiException ae) {
            // 领取先提交：任务冻结 v2 快照 {a:3}->{b:3}，修改因进行中任务被拒
            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ae.code()).isEqualTo("DEVICE_TASK_IN_PROGRESS");
            assertThat(pull.task().configVersion()).isEqualTo(2);
            assertThat(pull.task().configBefore()).isEqualTo(Map.of("a", 3));
            assertThat(pull.task().configAfter()).isEqualTo(Map.of("b", 3));
        } else {
            // 修改先提交：配置推进到 v3 {a:5}，领取冻结 v3 快照 {a:5}->{b:5}
            assertThat(modifyResult).isInstanceOf(DeviceConfigView.class);
            assertThat(pull.task().configVersion()).isEqualTo(3);
            assertThat(pull.task().configBefore()).isEqualTo(Map.of("a", 5));
            assertThat(pull.task().configAfter()).isEqualTo(Map.of("b", 5));
        }

        // 成功回执应用冻结快照：最终配置与领取时预演结果一致，版本推进一位
        int frozenVersion = pull.task().configVersion();
        Map<String, Integer> frozenAfter = pull.task().configAfter();
        taskService.receipt(pull.task().taskId(), new ReceiptRequest("req-rc", ReceiptResult.SUCCESS));
        DeviceConfigView config = configService.get("d1");
        assertThat(config.entries()).isEqualTo(frozenAfter);
        assertThat(config.configVersion()).isEqualTo(frozenVersion + 1);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = " + releaseId, Long.class)).isEqualTo(1);
    }

    @Test
    void 并发同requestId配置修改_重放一致且版本只推进一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> configService.modify("d1",
                    new ConfigModifyRequest("req-same", "tester", 1, Map.of("a", 3))));
        }
        List<Object> results = runConcurrently(tasks);

        DeviceConfigView first = null;
        for (Object result : results) {
            assertThat(result).isInstanceOf(DeviceConfigView.class);
            DeviceConfigView view = (DeviceConfigView) result;
            if (first == null) {
                first = view;
            } else {
                assertThat(view).isEqualTo(first);
            }
        }
        assertThat(configService.get("d1").configVersion()).isEqualTo(2);
        assertThat(configService.get("d1").entries()).isEqualTo(Map.of("a", 3));
    }

    @Test
    void 并发成功回执与配置修改_修改始终409且最终为冻结快照() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        configService.modify("d1", new ConfigModifyRequest("req-c1", "tester", 1, Map.of("a", 3)));
        createReleaseWithRename("req-r", "m1");
        long taskId = taskService.pull("d1", "req-p").task().taskId();

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.receipt(taskId,
                        new ReceiptRequest("req-rc", ReceiptResult.SUCCESS)),
                (Callable<Object>) () -> configService.modify("d1",
                        new ConfigModifyRequest("req-c2", "tester", 2, Map.of("a", 9)))));

        // 修改无论先后都失败：任务进行中（409 DEVICE_TASK_IN_PROGRESS）或版本已推进（409 CONFIG_VERSION_CONFLICT）
        Object modifyResult = results.get(1);
        assertThat(modifyResult).isInstanceOfSatisfying(ApiException.class, ae -> {
            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ae.code()).isIn("DEVICE_TASK_IN_PROGRESS", "CONFIG_VERSION_CONFLICT");
        });

        DeviceConfigView config = configService.get("d1");
        assertThat(config.entries()).isEqualTo(Map.of("b", 3));
        assertThat(config.configVersion()).isEqualTo(3);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");
    }
}
