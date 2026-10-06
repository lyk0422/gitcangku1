package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.MaintenanceRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全索引并发边界测试：维护与回执真实并发打到 H2 行锁上，验证联合裁决的一致提交顺序。
 */
@SpringBootTest
class FirmwareSecurityConcurrencyTest {

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
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

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

    @Test
    void 并发维护与成功回执_设备行锁串行化且恰好一个生效() throws Exception {
        int receiptFirst = 0;
        int maintenanceFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model,
                    "1.0.0", 5, 3));
            releaseService.create(new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0",
                    100, null, null, 4));
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-s" + seq, ReceiptResult.SUCCESS, 4)),
                    () -> deviceService.maintenance(deviceId,
                            new MaintenanceRequest("req-m" + seq, "ops", 1, 5))));

            Object receiptResult = results.get(0);
            Object maintenanceResult = results.get(1);
            var device = deviceService.get(deviceId);
            var task = taskRepository.findById(taskId).orElseThrow();
            // 设备行锁串行化：回执与维护都推进安全档案版本，expectedDeviceVersion=1 只允许一个生效
            assertThat(device.securityVersion()).isEqualTo(2);
            if (task.status() == TaskStatus.SUCCESS) {
                // 回执先提交：安装落库（底线 max(3,4)=4），维护因版本已推进而 409
                receiptFirst++;
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
                assertThat(maintenanceResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("VERSION_CONFLICT"));
                assertThat(device.currentVersion()).isEqualTo("2.0.0");
                assertThat(device.securityFloor()).isEqualTo(4);
                assertThat(task.proofIndex()).isEqualTo(4);
            } else {
                // 维护先提交：底线 5 高于冻结目标 4，回执 409，任务仍 PENDING
                maintenanceFirst++;
                assertThat(maintenanceResult).isNotInstanceOf(Exception.class);
                assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("SECURITY_FLOOR_RAISED");
                });
                assertThat(device.currentVersion()).isEqualTo("1.0.0");
                assertThat(device.securityFloor()).isEqualTo(5);
            }
        }
        assertThat(receiptFirst + maintenanceFirst).isEqualTo(10);
    }

    @Test
    void 并发同requestId维护_重放一致且版本只加一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5, 3));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.maintenance("d1",
                    new MaintenanceRequest("req-same", "ops", 1, 6)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        var device = deviceService.get("d1");
        assertThat(device.securityFloor()).isEqualTo(6);
        assertThat(device.securityVersion()).isEqualTo(2);
    }

    @Test
    void 并发维护_不同requestId竞争同一版本_只有一个成功() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5, 3));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> deviceService.maintenance("d1",
                    new MaintenanceRequest("req-m" + seq, "ops", 1, 6 + seq)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        var device = deviceService.get("d1");
        assertThat(device.securityVersion()).isEqualTo(2);
        assertThat(device.securityFloor()).isIn(6, 7);
    }
}
