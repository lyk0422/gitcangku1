package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ReleaseQuarantineRequest;
import com.example.starter.firmware.domain.DeviceFailureRoundStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceFailureRoundRepository;
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

/**
 * 设备隔离并发与幂等边界测试：真实并发打到 H2 行锁与唯一约束上。
 * 验证第二次失败与拉取竞争后不存在“隔离后仍可领取（PENDING）”的任务、
 * 解除与第二次失败竞争的一致结果、同 requestId 第二次失败并发重放只隔离一次。
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
    private DeviceFailureRoundRepository roundRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_failure_round");
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

    private long prepareCountingDevice(String deviceId) {
        deviceService.register(new RegisterDeviceRequest("req-dev-" + deviceId, deviceId,
                "m-" + deviceId, "1.0.0", 1));
        long release1 = releaseService.create(new CreateReleaseRequest(
                "req-r1-" + deviceId, "m-" + deviceId, "1.0.0", "2.0.0", 100, 100, 100)).releaseId();
        long task1 = taskService.pull(deviceId, "req-pull1-" + deviceId).task().taskId();
        taskService.receipt(task1, new ReceiptRequest("req-fail1-" + deviceId, ReceiptResult.FAILED,
                "bob", "err-1"));
        releaseService.cancel(release1, "req-cancel1-" + deviceId);
        long release2 = releaseService.create(new CreateReleaseRequest(
                "req-r2-" + deviceId, "m-" + deviceId, "1.0.0", "2.0.0", 100, 100, 100)).releaseId();
        long task2 = taskService.pull(deviceId, "req-pull2-" + deviceId).task().taskId();
        var counting = roundRepository.findLatest(deviceId, "2.0.0").orElseThrow();
        assertThat(counting.status()).isEqualTo(DeviceFailureRoundStatus.COUNTING);
        assertThat(counting.consecutiveFailures()).isEqualTo(1);
        return task2;
    }

    @Test
    void 第二次失败与并发拉取竞争_隔离后不存在PENDING任务_后续拉取409() throws Exception {
        int rounds = 10;
        for (int round = 0; round < rounds; round++) {
            String deviceId = "d" + round;
            long task2 = prepareCountingDevice(deviceId);
            final long taskId = task2;
            final int roundNo = round;

            // 1 个线程提交第二次失败，其余线程同时拉取
            List<Callable<Object>> tasks = new ArrayList<>();
            tasks.add(() -> taskService.receipt(taskId,
                    new ReceiptRequest("req-fail2-" + roundNo, ReceiptResult.FAILED, "bob", "err-2")));
            for (int i = 0; i < 5; i++) {
                final int seq = i;
                tasks.add(() -> taskService.pull(deviceId, "req-pull-race-" + roundNo + "-" + seq));
            }
            List<Object> results = runConcurrently(tasks);

            // 第二次失败必定成功
            assertThat(results.get(0)).as("第二次失败回执（第%d轮）", round).isNotInstanceOf(Exception.class);

            // 拉取结果只有两类：隔离前返回已有 task2，或隔离后 409 DEVICE_QUARANTINED；不得新建任务
            for (int i = 1; i < results.size(); i++) {
                Object result = results.get(i);
                if (result instanceof PullResponse response) {
                    assertThat(response.task()).isNotNull();
                    assertThat(response.task().taskId()).isEqualTo(taskId);
                } else {
                    assertThat(result).isInstanceOfSatisfying(ApiException.class, ae -> {
                        assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(ae.code()).isEqualTo("DEVICE_QUARANTINED");
                    });
                }
            }

            // 最终业务不变量：已隔离，且不存在“隔离后仍可领取”的 PENDING 任务
            var latest = roundRepository.findLatest(deviceId, "2.0.0").orElseThrow();
            assertThat(latest.status()).isEqualTo(DeviceFailureRoundStatus.QUARANTINED);
            assertThat(latest.consecutiveFailures()).isEqualTo(2);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task t JOIN release_order r ON t.release_id = r.id"
                            + " WHERE t.device_id = ? AND r.to_version = '2.0.0' AND t.status = 'PENDING'",
                    Long.class, deviceId);
            assertThat(pending).as("隔离后不得残留 PENDING 任务（第%d轮）", round).isZero();
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task t JOIN release_order r ON t.release_id = r.id"
                            + " WHERE t.device_id = ? AND r.to_version = '2.0.0'",
                    Long.class, deviceId);
            assertThat(taskCount).as("竞争中不得新建第三个任务（第%d轮）", round).isEqualTo(2);

            // 竞争结束后再拉取：稳定 409
            try {
                taskService.pull(deviceId, "req-pull-after-" + round);
                throw new AssertionError("隔离设备拉取应返回 409（第" + round + "轮）");
            } catch (ApiException e) {
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.code()).isEqualTo("DEVICE_QUARANTINED");
            }
        }
    }

    @Test
    void 同requestId第二次失败并发重放_只固化一次隔离() throws Exception {
        String deviceId = "dq";
        long task2 = prepareCountingDevice(deviceId);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receipt(task2,
                    new ReceiptRequest("req-same-fail2", ReceiptResult.FAILED, "bob", "err-2")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }

        var latest = roundRepository.findLatest(deviceId, "2.0.0").orElseThrow();
        assertThat(latest.status()).isEqualTo(DeviceFailureRoundStatus.QUARANTINED);
        assertThat(latest.consecutiveFailures()).isEqualTo(2);
        assertThat(latest.secondTaskId()).isEqualTo(task2);
        assertThat(latest.confirmedBy()).isEqualTo("bob");
        Long rounds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_failure_round WHERE device_id = ?", Long.class, deviceId);
        assertThat(rounds).isEqualTo(1);
        // 发布单失败统计也只计一次
        Long failed = jdbc.queryForObject(
                "SELECT r.round_failed FROM rollout_task t JOIN release_order r ON t.release_id = r.id"
                        + " WHERE t.id = ?",
                Long.class, task2);
        assertThat(failed).isEqualTo(1);
    }

    @Test
    void 解除与第二次失败并发_按设备行锁提交顺序产生唯一一致结果() throws Exception {
        int releaseRanFirst = 0;
        int failureRanFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "dr" + round;
            long task2 = prepareCountingDevice(deviceId);
            final long taskId = task2;
            final int roundNo = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-fail2-" + roundNo, ReceiptResult.FAILED, "bob", "err-2")),
                    (Callable<Object>) () -> quarantineService.release(deviceId,
                            new ReleaseQuarantineRequest("req-rel-" + roundNo, "carol", "复核通过"))));

            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
            var latest = roundRepository.findLatest(deviceId, "2.0.0").orElseThrow();
            Object releaseResult = results.get(1);
            if (latest.status() == DeviceFailureRoundStatus.QUARANTINED) {
                // 解除先拿到设备锁时轮次尚为 COUNTING：解除 409 NOT_QUARANTINED 不占键，随后失败完成隔离
                releaseRanFirst++;
                assertThat(releaseResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("NOT_QUARANTINED");
                });
                assertThat(latest.consecutiveFailures()).isEqualTo(2);
                // 仍隔离：拉取 409
                assertThatThrownByConflict(deviceId, "req-pull-q-" + round);
            } else {
                // 失败先提交隔离，解除随后成功：只开新一轮，历史两次失败保留
                failureRanFirst++;
                assertThat(latest.status()).isEqualTo(DeviceFailureRoundStatus.RELEASED);
                assertThat(releaseResult).isNotInstanceOf(Exception.class);
                assertThat(latest.consecutiveFailures()).isEqualTo(2);
                assertThat(latest.confirmedBy()).isEqualTo("bob");
                assertThat(latest.releasedBy()).isEqualTo("carol");
                // 同发布单已留有 FAILED 任务，新一轮任务来自取消后新建的发布单
                long release2Id = jdbc.queryForObject(
                        "SELECT id FROM release_order WHERE active_model = ?", Long.class, "m-" + deviceId);
                releaseService.cancel(release2Id, "req-cancel2-" + roundNo);
                long release3Id = releaseService.create(new CreateReleaseRequest(
                        "req-r3-" + roundNo, "m-" + deviceId, "1.0.0", "2.0.0", 100, 100, 100)).releaseId();
                PullResponse response = taskService.pull(deviceId, "req-pull-ok-" + roundNo);
                assertThat(response.task()).isNotNull();
                assertThat(response.task().releaseId()).isEqualTo(release3Id);
                assertThat(response.task().status()).isEqualTo("PENDING");
                // 仅领取新任务时尚无失败：历史轮次保持 RELEASED，新一轮在第一次失败时才开启
                var keptRound = roundRepository.findLatest(deviceId, "2.0.0").orElseThrow();
                assertThat(keptRound.roundNo()).isEqualTo(1);
                assertThat(keptRound.status()).isEqualTo(DeviceFailureRoundStatus.RELEASED);
                assertThat(keptRound.consecutiveFailures()).isEqualTo(2);
            }
        }
        assertThat(releaseRanFirst + failureRanFirst).isEqualTo(10);
    }

    private void assertThatThrownByConflict(String deviceId, String requestId) {
        try {
            taskService.pull(deviceId, requestId);
            throw new AssertionError("隔离设备拉取应返回 409: " + deviceId);
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("DEVICE_QUARANTINED");
        }
    }
}
