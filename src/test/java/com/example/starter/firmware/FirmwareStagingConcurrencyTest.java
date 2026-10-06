package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateDeviceSpaceRequest;
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
 * 暂存空间并发边界测试：维护、领取、回执和取消共同锁定设备空间行，
 * 真实并发打到 H2 事务上，核验容量不变式与最终数据。
 */
@SpringBootTest
class FirmwareStagingConcurrencyTest {

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

    private void registerTracked(String requestId, String deviceId, String model,
                                 long totalSpace, long currentImage) {
        deviceService.register(new RegisterDeviceRequest(requestId, deviceId, model, "1.0.0", 1,
                totalSpace, currentImage));
    }

    private long createTrackedRelease(String requestId, String model, long packageBytes, long imageBytes) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 100,
                null, null, packageBytes, imageBytes)).releaseId();
    }

    @Test
    void 并发领取与维护_空间行锁串行且容量不变式成立() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            registerTracked("req-d" + round, deviceId, model, 110, 40);
            createTrackedRelease("req-r" + round, model, 20, 50);
            final int seq = round;

            // 领取峰值 110 恰好等于当前容量；维护到 105 仅在无活跃预留时合法
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> deviceService.updateSpace(deviceId,
                            new UpdateDeviceSpaceRequest("req-u" + seq, 105L, 1))));

            Object pullResult = results.get(0);
            Object updateResult = results.get(1);
            DeviceView device = deviceService.get(deviceId);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND status = 'PENDING'",
                    Long.class, deviceId);
            if (pending == 1) {
                // 领取先提交：任务与预留生成，维护因容量低于占用被拒
                assertThat(pullResult).isInstanceOf(PullResponse.class);
                assertThat(updateResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("SPACE_BELOW_IN_USE");
                    assertThat(ae.expectedDeviceVersion()).isEqualTo(1);
                });
                assertThat(device.totalSpaceBytes()).isEqualTo(110);
                assertThat(device.availableBytes()).isZero();
                assertThat(device.spaceVersion()).isEqualTo(1);
            } else {
                // 维护先提交：容量降为 105，领取 422 且不生成任务或预留
                assertThat(pending).isZero();
                assertThat(updateResult).isInstanceOf(DeviceView.class);
                assertThat(pullResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ae.code()).isEqualTo("INSUFFICIENT_SPACE");
                });
                assertThat(device.totalSpaceBytes()).isEqualTo(105);
                assertThat(device.availableBytes()).isEqualTo(65);
                assertThat(device.spaceVersion()).isEqualTo(2);
            }
        }
    }

    @Test
    void 并发成功回执与取消_空间版本与余量最终一致() throws Exception {
        int successWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            registerTracked("req-d" + round, deviceId, model, 200, 40);
            long releaseId = createTrackedRelease("req-r" + round, model, 20, 50);
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-s" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));

            Object receiptResult = results.get(0);
            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            DeviceView device = deviceService.get(deviceId);
            assertThat(finalStatus).isNotEqualTo(TaskStatus.PENDING);
            // 无论谁先提交，首次任务终结恰好一次：空间档案版本从 1 变为 2
            assertThat(device.spaceVersion()).isEqualTo(2);
            if (finalStatus == TaskStatus.SUCCESS) {
                successWins++;
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
                assertThat(device.currentVersion()).isEqualTo("2.0.0");
                assertThat(device.currentImageBytes()).isEqualTo(50);
                assertThat(device.availableBytes()).isEqualTo(150);
            } else {
                cancelWins++;
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(device.currentVersion()).isEqualTo("1.0.0");
                assertThat(device.currentImageBytes()).isEqualTo(40);
                assertThat(device.availableBytes()).isEqualTo(160);
            }
            assertThat(device.activeReservedBytes()).isZero();
        }
        assertThat(successWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 并发同requestId维护_重放一致且版本只加一次() throws Exception {
        registerTracked("req-d", "d1", "m1", 100, 40);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.updateSpace("d1",
                    new UpdateDeviceSpaceRequest("req-same", 110L, 1)));
        }
        List<Object> results = runConcurrently(tasks);

        DeviceView first = null;
        for (Object result : results) {
            assertThat(result).isInstanceOf(DeviceView.class);
            DeviceView view = (DeviceView) result;
            if (first == null) {
                first = view;
            } else {
                assertThat(view).isEqualTo(first);
            }
        }
        assertThat(first.spaceVersion()).isEqualTo(2);
        // 版本只加一次，证明重放而非重复执行
        assertThat(deviceService.get("d1").spaceVersion()).isEqualTo(2);
        assertThat(deviceService.get("d1").totalSpaceBytes()).isEqualTo(110);
    }

    @Test
    void 并发领取_同设备至多一项活跃暂存且预留只登记一次() throws Exception {
        registerTracked("req-d", "d1", "m1", 110, 40);
        long releaseId = createTrackedRelease("req-r", "m1", 20, 50);

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
            assertThat(response.task().reservedBytes()).isEqualTo(70);
            assertThat(response.task().peakBytes()).isEqualTo(110);
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
        assertThat(taskRepository.sumActiveReservedBytes("d1")).isEqualTo(70);
        assertThat(deviceService.get("d1").availableBytes()).isZero();
    }
}
