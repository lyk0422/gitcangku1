package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.QuarantineRound;
import com.example.starter.firmware.domain.QuarantineStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.QuarantineRepository;
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
 * 设备隔离并发与幂等边界测试：真实并发打到 H2 行锁与唯一约束上。
 * 重点验证第二次失败与新任务领取竞争时，不会产生隔离后仍可领取的任务。
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
    private TaskRepository taskRepository;

    @Autowired
    private QuarantineRepository quarantineRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM device_quarantine_round");
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

    private long setupTrackingWithPendingSecondTask(String deviceId, String model) {
        long releaseB = setupActiveSecondReleaseWithoutTask(deviceId, model);
        long t2 = taskService.pull(deviceId, "req-p2-pending-" + deviceId).task().taskId();
        assertThat(taskRepository.findById(t2).orElseThrow().status()).isEqualTo(TaskStatus.PENDING);
        assertThat(releaseB).isPositive();
        return t2;
    }

    private long setupActiveSecondReleaseWithoutTask(String deviceId, String model) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + deviceId, deviceId, model, "1.0.0", 1));
        long releaseA = releaseService.create(
                new CreateReleaseRequest("req-a-" + deviceId, model, "1.0.0", "2.0.0", 100)).releaseId();
        long t1 = taskService.pull(deviceId, "req-p1-" + deviceId).task().taskId();
        taskService.receipt(t1, new ReceiptRequest("req-f1-" + deviceId, ReceiptResult.FAILED, "E1", null));
        releaseService.cancel(releaseA, "req-cancel-a-" + deviceId);
        // 第二张 ACTIVE 发布单，设备尚未领取任务；第二次失败的“领取+回执”与竞争拉取在此真实竞争插入
        long releaseB = releaseService.create(
                new CreateReleaseRequest("req-b-" + deviceId, model, "1.0.0", "2.0.0", 100)).releaseId();
        Long taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseB);
        assertThat(taskCount).isZero();
        return releaseB;
    }

    @Test
    void 第二次失败与新任务领取竞争_不得产生隔离后仍可领取的任务() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            long releaseB = setupActiveSecondReleaseWithoutTask(deviceId, model);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    // 复合动作：领取第二次失败任务并立即回执隔离（领取与回执是两次加锁请求）
                    (Callable<Object>) () -> {
                        long taskId = taskService.pull(deviceId, "req-p2-" + seq).task().taskId();
                        return taskService.receipt(taskId,
                                new ReceiptRequest("req-f2-" + seq, ReceiptResult.FAILED, "E2", "alice"));
                    },
                    // 竞争拉取：可能在隔离提交前抢到同一任务，或在隔离提交后得到 409
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-pull-race-" + seq)));

            Object receiptResult = results.get(0);
            Object pullResult = results.get(1);
            assertThat(receiptResult).as("第二次失败回执第%d轮必须成功", round).isNotInstanceOf(Exception.class);

            // 拉取一方：要么拿到竞争中创建的同一条任务（是否已被终结为 FAILED 取决于与回执提交的先后，
            // 隔离提交前读到 PENDING 属合法窗口，最终该任务由回执事务终结）；要么在隔离提交后得到 409。
            // 无论哪种，都不得产生第二条任务。
            if (pullResult instanceof PullResponse response) {
                assertThat(response.task()).isNotNull();
            } else {
                assertThat(pullResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("DEVICE_QUARANTINED");
                });
            }

            // 最终：该发布单恰好一条任务且已 FAILED；设备 QUARANTINED 计数2；不存在任何 PENDING 任务
            List<Long> taskIds = jdbc.queryForList(
                    "SELECT id FROM rollout_task WHERE release_id = ? ORDER BY id", Long.class, releaseB);
            assertThat(taskIds).as("同设备同发布单至多一条任务（第%d轮）", round).hasSize(1);
            long t2 = taskIds.get(0);
            assertThat(taskRepository.findById(t2).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
            QuarantineRound active = quarantineRepository.findActive(deviceId, "2.0.0").orElseThrow();
            assertThat(active.status()).isEqualTo(QuarantineStatus.QUARANTINED);
            assertThat(active.consecutiveFailures()).isEqualTo(2);
            assertThat(active.firstTaskId()).isNotNull();
            assertThat(active.secondTaskId()).isEqualTo(t2);
            assertThat(active.secondReason()).isEqualTo("E2");
            assertThat(active.confirmedBy()).isEqualTo("alice");
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND status = 'PENDING'",
                    Long.class, deviceId);
            assertThat(pending).as("隔离后不得残留可领取任务（第%d轮）", round).isZero();

            // 隔离落定后再次拉取稳定 409
            ApiException later = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> taskService.pull(deviceId, "req-pull-after-" + seq), ApiException.class);
            assertThat(later.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(later.code()).isEqualTo("DEVICE_QUARANTINED");
        }
    }

    @Test
    void 同一失败回执requestId并发重放_首次计数和状态只发生一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        long taskId = taskService.pull("d1", "req-pull").task().taskId();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receipt(taskId,
                    new ReceiptRequest("req-same-fail", ReceiptResult.FAILED, "E1", null)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allMatch(r -> !(r instanceof Exception));

        // 任务 FAILED，轮次只有一条且计数1，回执副作用只发生一次
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        List<QuarantineRound> rounds = quarantineRepository.findByDevice("d1");
        assertThat(rounds).hasSize(1);
        assertThat(rounds.get(0).status()).isEqualTo(QuarantineStatus.TRACKING);
        assertThat(rounds.get(0).consecutiveFailures()).isEqualTo(1);
        assertThat(rounds.get(0).firstTaskId()).isEqualTo(taskId);
        assertThat(releaseService.findOrder(releaseId).roundFailed()).isEqualTo(1);
    }

    @Test
    void 第二次失败回执同requestId并发_隔离只生效一次() throws Exception {
        long t2 = setupTrackingWithPendingSecondTask("d1", "m9");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receipt(t2,
                    new ReceiptRequest("req-same-f2", ReceiptResult.FAILED, "E2", "alice")));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allMatch(r -> !(r instanceof Exception));

        List<QuarantineRound> rounds = quarantineRepository.findByDevice("d1");
        assertThat(rounds).hasSize(1);
        QuarantineRound round = rounds.get(0);
        assertThat(round.status()).isEqualTo(QuarantineStatus.QUARANTINED);
        assertThat(round.consecutiveFailures()).isEqualTo(2);
        assertThat(round.secondTaskId()).isEqualTo(t2);
        assertThat(round.confirmedBy()).isEqualTo("alice");
        assertThat(taskRepository.findById(t2).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
    }
}
