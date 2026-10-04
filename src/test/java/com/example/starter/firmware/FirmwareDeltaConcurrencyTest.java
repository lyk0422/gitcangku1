package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateDeltaReleaseRequest;
import com.example.starter.firmware.api.MaintenanceRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
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
 * 增量发布并发边界测试：维护更新与拉取、安装回执在真实 H2 行锁上串行，
 * 并发相同回执最多生效一次，核对业务结果与最终数据。
 */
@SpringBootTest
class FirmwareDeltaConcurrencyTest {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_P = "d".repeat(64);
    private static final String DIGEST_T = "e".repeat(64);

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

    private long setupDeltaTask(String suffix) {
        deviceService.register(new RegisterDeviceRequest("req-d" + suffix, "d" + suffix, "m" + suffix,
                "1.0.0", DIGEST_A, 1));
        long releaseId = releaseService.createDelta(new CreateDeltaReleaseRequest("req-r" + suffix,
                "m" + suffix, "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T, null, null)).releaseId();
        return taskService.pull("d" + suffix, "req-p" + suffix).task().taskId();
    }
    @Test
    void 并发维护与成功回执_串行且最终状态一致() throws Exception {
        int receiptWins = 0;
        int maintenanceWins = 0;
        for (int round = 0; round < 10; round++) {
            String suffix = "-" + round;
            long taskId = setupDeltaTask(suffix);
            String deviceId = "d" + suffix;
            long releaseId = taskRepository.findById(taskId).orElseThrow().releaseId();

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> deviceService.maintenance(deviceId,
                            new MaintenanceRequest("req-m" + suffix, "ops", "1.0.1", DIGEST_C, 1)),
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-s" + suffix, ReceiptResult.SUCCESS, 1,
                                    DIGEST_A, DIGEST_T))));

            Object maintenanceResult = results.get(0);
            Object receiptResult = results.get(1);
            Device device = deviceService.findDevice(deviceId);
            TaskStatus taskStatus = taskRepository.findById(taskId).orElseThrow().status();
            ReleaseOrder order = releaseService.findOrder(releaseId);

            // 镜像代次恰好加一：要么维护生效，要么安装成功生效，不会两者都生效
            assertThat(device.imageGeneration()).isEqualTo(2);
            if (receiptResult instanceof ApiException ae) {
                // 维护先提交：基于旧镜像的回执被拒绝，任务未终结、不统计成功
                maintenanceWins++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("DEVICE_IMAGE_CHANGED");
                assertThat(maintenanceResult).isNotInstanceOf(Exception.class);
                assertThat(device.currentDigest()).isEqualTo(DIGEST_C);
                assertThat(device.currentVersion()).isEqualTo("1.0.1");
                assertThat(taskStatus).isEqualTo(TaskStatus.PENDING);
                assertThat(order.roundSuccess()).isZero();
            } else {
                // 回执先提交：设备写入目标摘要，维护因期望代次过时而 409
                receiptWins++;
                assertThat(maintenanceResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ae.code()).isEqualTo("GENERATION_CONFLICT");
                        });
                assertThat(device.currentDigest()).isEqualTo(DIGEST_T);
                assertThat(device.currentVersion()).isEqualTo("2.0.0");
                assertThat(taskStatus).isEqualTo(TaskStatus.SUCCESS);
                assertThat(order.roundSuccess()).isEqualTo(1);
            }
        }
        assertThat(receiptWins + maintenanceWins).isEqualTo(10);
    }

    @Test
    void 并发相同成功回执_最多生效一次_重放一致() throws Exception {
        long taskId = setupDeltaTask("");
        long releaseId = taskRepository.findById(taskId).orElseThrow().releaseId();

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receipt(taskId,
                    new ReceiptRequest("req-same", ReceiptResult.SUCCESS, 1, DIGEST_A, DIGEST_T)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        // 设备只更新一次：代次从 1 变为 2，统计只加一次
        Device device = deviceService.findDevice("d");
        assertThat(device.currentVersion()).isEqualTo("2.0.0");
        assertThat(device.currentDigest()).isEqualTo(DIGEST_T);
        assertThat(device.imageGeneration()).isEqualTo(2);
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
    }

    @Test
    void 并发拉取与维护_串行不生成基于旧镜像的任务() throws Exception {
        int pullFirst = 0;
        int maintenanceFirst = 0;
        for (int round = 0; round < 10; round++) {
            String suffix = "-" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + suffix, "d" + suffix, "m" + suffix,
                    "1.0.0", DIGEST_A, 1));
            long releaseId = releaseService.createDelta(new CreateDeltaReleaseRequest("req-r" + suffix,
                    "m" + suffix, "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T, null, null))
                    .releaseId();
            String deviceId = "d" + suffix;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + suffix),
                    (Callable<Object>) () -> deviceService.maintenance(deviceId,
                            new MaintenanceRequest("req-m" + suffix, "ops", "1.0.1", DIGEST_C, 1))));

            Object pullResult = results.get(0);
            Device device = deviceService.findDevice(deviceId);
            long taskCount = taskRepository.countByReleaseAndDevice(releaseId, deviceId);
            if (pullResult instanceof ApiException ae) {
                // 维护先提交：版本与摘要均已变更，拉取 422 且不生成任务
                maintenanceFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isIn("VERSION_MISMATCH", "DIGEST_MISMATCH");
                assertThat(taskCount).isZero();
                assertThat(device.currentDigest()).isEqualTo(DIGEST_C);
                assertThat(device.imageGeneration()).isEqualTo(2);
            } else {
                // 拉取先提交：任务冻结代次 1 与全 a 基底，维护随后生效
                pullFirst++;
                PullResponse response = (PullResponse) pullResult;
                assertThat(response.task()).isNotNull();
                assertThat(response.task().imageGeneration()).isEqualTo(1);
                assertThat(response.task().baseDigest()).isEqualTo(DIGEST_A);
                assertThat(taskCount).isEqualTo(1);
                assertThat(device.currentDigest()).isEqualTo(DIGEST_C);
                assertThat(device.imageGeneration()).isEqualTo(2);
            }
        }
        assertThat(pullFirst + maintenanceFirst).isEqualTo(10);
    }
}