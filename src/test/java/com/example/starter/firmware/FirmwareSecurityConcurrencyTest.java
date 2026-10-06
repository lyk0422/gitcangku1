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
    void 并发同requestId维护_重放一致且档案版本只加一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5, 1));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.maintain("d1",
                    new MaintenanceRequest("req-same", "ops", 1, 5)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        var device = deviceService.get("d1");
        assertThat(device.securityFloor()).isEqualTo(5);
        assertThat(device.securityProfileVersion()).isEqualTo(2);
    }

    @Test
    void 并发不同键维护_唯一成功且版本不丢更新() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5, 1));

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> deviceService.maintain("d1",
                        new MaintenanceRequest("req-a", "ops", 1, 7)),
                (Callable<Object>) () -> deviceService.maintain("d1",
                        new MaintenanceRequest("req-b", "ops", 1, 9))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        // 只有一次维护生效：档案版本只加一次，底线为胜者的值
        var device = deviceService.get("d1");
        assertThat(device.securityProfileVersion()).isEqualTo(2);
        assertThat(device.securityFloor()).isIn(7, 9);
    }

    @Test
    void 并发维护与成功回执_一致提交顺序且底线不降() throws Exception {
        int receiptWins = 0;
        int maintainWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 5, 3));
            releaseService.create(new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0",
                    100, null, null, 4));
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc" + seq, ReceiptResult.SUCCESS, 4)),
                    (Callable<Object>) () -> deviceService.maintain(deviceId,
                            new MaintenanceRequest("req-mt" + seq, "ops", 1, 5))));

            Object receiptResult = results.get(0);
            Object maintainResult = results.get(1);
            var device = deviceService.get(deviceId);
            var task = taskRepository.findById(taskId).orElseThrow();
            if (task.status() == TaskStatus.SUCCESS) {
                // 回执先提交：安装按当时底线 3 提交成功，维护因档案版本已推进而 409
                receiptWins++;
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
                assertThat(device.currentVersion()).isEqualTo("2.0.0");
                assertThat(device.securityFloor()).isEqualTo(4);
                assertThat(device.securityProfileVersion()).isEqualTo(2);
                assertThat(maintainResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("DEVICE_VERSION_CONFLICT"));
            } else {
                // 维护先提交：底线 5 高于冻结索引 4，回执 409 且任务不被伪造失败
                maintainWins++;
                assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("SECURITY_FLOOR_CONFLICT");
                });
                assertThat(maintainResult).isNotInstanceOf(Exception.class);
                assertThat(device.currentVersion()).isEqualTo("1.0.0");
                assertThat(device.securityFloor()).isEqualTo(5);
                assertThat(device.securityProfileVersion()).isEqualTo(2);
            }
            // 无论谁先提交：底线单调不降，监控样本与任务状态一致
            assertThat(device.securityFloor()).isGreaterThanOrEqualTo(4);
        }
        assertThat(receiptWins + maintainWins).isEqualTo(10);
    }
}
