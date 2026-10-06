package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.MaintainSpaceRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.StagingReservationRepository;
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
 * 暂存空间并发边界：真实并发打到 H2 行锁与唯一约束上，
 * 验证同设备至多一项活跃暂存、维护与领取互斥、回执与取消的一致提交顺序。
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
    private StagingReservationRepository stagingReservationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM staging_reservation");
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

    private void registerWithSpace(String requestId, String deviceId, String model, long total, long image) {
        deviceService.register(new RegisterDeviceRequest(requestId, deviceId, model, "1.0.0", 5,
                total, image));
    }

    private long createReleaseWithSpace(String requestId, String model, long packageBytes, long imageBytes) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 100,
                null, null, packageBytes, imageBytes)).releaseId();
    }

    @Test
    void 并发领取_同设备至多一项活跃暂存() throws Exception {
        registerWithSpace("req-d", "d1", "m1", 110, 40);
        long releaseId = createReleaseWithSpace("req-r", "m1", 20, 50);

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
            assertThat(response.task().peakBytes()).isEqualTo(110L);
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
        assertThat(stagingReservationRepository.countByDeviceId("d1")).isEqualTo(1);
    }

    @Test
    void 并发维护与领取_最终数据一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            registerWithSpace("req-d" + round, deviceId, model, 100, 40);
            long releaseId = createReleaseWithSpace("req-r" + round, model, 20, 50);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> deviceService.maintainSpace(deviceId,
                            new MaintainSpaceRequest("req-m" + seq, "ops", 110L, 40L, 1)),
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq)));

            Object pullResult = results.get(1);
            long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
            long reservationCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM staging_reservation WHERE release_id = ?",
                    Long.class, releaseId);
            if (pullResult instanceof ApiException ae) {
                // 领取先通过：容量仍 100，422 且不生成任务或预留
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(taskCount).as("第%d轮", seq).isZero();
                assertThat(reservationCount).as("第%d轮", seq).isZero();
            } else {
                // 维护先提交：领取成功，任务与预留成对出现
                assertThat(pullResult).isInstanceOf(PullResponse.class);
                assertThat(taskCount).as("第%d轮", seq).isEqualTo(1);
                assertThat(reservationCount).as("第%d轮", seq).isEqualTo(1);
            }
            // 维护必然成功（无并发冲突方）
            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
        }
    }

    @Test
    void 并发成功回执与取消_预留恰释放一次且镜像状态一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            registerWithSpace("req-d" + round, deviceId, model, 110, 40);
            long releaseId = createReleaseWithSpace("req-r" + round, model, 20, 50);
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-s" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));

            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            var device = deviceService.get(deviceId);
            // 无论谁先提交：预留都已释放，档案版本恰好加一
            assertThat(stagingReservationRepository.countByDeviceId(deviceId)).isZero();
            assertThat(device.spaceVersion()).isEqualTo(2);
            if (finalStatus == TaskStatus.SUCCESS) {
                assertThat(results.get(0)).isNotInstanceOf(Exception.class);
                assertThat(device.currentImageBytes()).isEqualTo(50);
                assertThat(device.availableBytes()).isEqualTo(60);
            } else {
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(device.currentImageBytes()).isEqualTo(40);
                assertThat(device.availableBytes()).isEqualTo(70);
            }
        }
    }
}
