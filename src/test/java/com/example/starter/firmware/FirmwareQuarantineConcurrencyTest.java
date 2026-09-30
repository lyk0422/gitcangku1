package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ReleaseQuarantineRequest;
import com.example.starter.firmware.domain.DeviceFailureRound;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RoundStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceFailureRoundRepository;
import com.example.starter.firmware.repo.TaskRepository;
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
 * 设备隔离并发与幂等边界测试：真实并发打到 H2 设备行锁、发布单行锁与唯一约束上，
 * 验证"第二次失败隔离"与"新任务领取"竞争时不会产生隔离后仍可领取的 PENDING 任务，
 * 以及并发解除、跨版本独立计数的一致性。
 */
@SpringBootTest
class FirmwareQuarantineConcurrencyTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private QuarantineService quarantineService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private DeviceFailureRoundRepository roundRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM device_failure_round");
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

    private void registerDevice(String deviceId) {
        jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?, 'm1', '1.0.0', 1)",
                deviceId);
    }

    private long createRelease(String requestId) {
        return releaseService.create(
                new CreateReleaseRequest(requestId, "m1", "1.0.0", "2.0.0", 100, 2, 100)).releaseId();
    }

    private long firstFailure(String deviceId, String pullReq, String receiptReq) {
        long releaseId = createRelease("c-" + receiptReq);
        long taskId = taskService.pull(deviceId, pullReq).task().taskId();
        taskService.receipt(taskId, new ReceiptRequest(receiptReq, ReceiptResult.FAILED, "原因", "op-a"));
        return releaseId;
    }

    /**
     * 核心并发：设备已有第一次失败（ACTIVE 轮次），R2 ACTIVE 且设备尚无任务。
     * "第二次失败回执（隔离）"与"新任务领取（拉取）"闩锁对齐并发：
     * - 隔离先提交：拉取必须 409，且库里不得存在 R2 的 PENDING 任务；
     * - 拉取先提交：任务创建成功，随后回执失败完成隔离，任务终态 FAILED；
     * 无论提交顺序如何，最终设备为 QUARANTINED，且不存在"隔离后仍可领取"的 PENDING 任务。
     */
    @Test
    void 第二次失败与新任务领取竞争_不得产生隔离后仍可领取的任务() throws Exception {
        int quarantineFirst = 0;
        int pullFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            registerDevice(deviceId);
            long r1 = firstFailure(deviceId, "p1-" + round, "f1-" + round);
            jdbc.update("UPDATE release_order SET status = 'CANCELLED', active_model = NULL WHERE id = ?", r1);
            long r2 = createRelease("c2-" + round);
            // 先建出 PENDING 任务但不回执，作为"第二次失败"线程的目标；领取线程与它竞争 R2 的任务槽位
            long taskForReceipt = taskService.pull(deviceId, "p2-" + round).task().taskId();

            // 为真正制造"领取新任务"的竞争面，删除该任务后两线程竞争：一个重新领取并失败，一个只领取
            jdbc.update("DELETE FROM rollout_task WHERE id = ?", taskForReceipt);
            final int seq = round;
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> {
                        long taskId = taskService.pull(deviceId, "pull-" + seq).task().taskId();
                        return taskService.receipt(taskId,
                                new ReceiptRequest("f2-" + seq, ReceiptResult.FAILED, "原因B", "op-b"));
                    },
                    (Callable<Object>) () -> taskService.pull(deviceId, "race-" + seq)));

            // 最终：设备隔离，R2 对该设备至多一条任务且不得为 PENDING
            DeviceFailureRound failureRound = roundRepository.findActive(deviceId, "2.0.0").orElseThrow();
            assertThat(failureRound.status()).isEqualTo(RoundStatus.QUARANTINED);
            assertThat(failureRound.consecutiveFailed()).isEqualTo(2);

            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ? AND status = 'PENDING'",
                    Long.class, r2, deviceId);
            assertThat(pending).as("第%d轮：隔离后不得残留可领取的 PENDING 任务", round).isZero();
            Long total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                    Long.class, r2, deviceId);
            assertThat(total).isEqualTo(1);

            // 拉取线程的结果：隔离先提交则为 409；拉取先提交则拿到任务（随后被另一线程失败终结）
            Object pullResult = results.get(1);
            if (pullResult instanceof ApiException ae) {
                quarantineFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("DEVICE_QUARANTINED");
            } else {
                pullFirst++;
                assertThat(pullResult).isNotInstanceOf(Exception.class);
            }
            // 隔离后再次拉取：稳定 409
            try {
                taskService.pull(deviceId, "after-" + round);
                throw new AssertionError("隔离后拉取必须 409");
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("DEVICE_QUARANTINED");
            }
            // 终结本轮发布单，释放型号活动槽位供下一轮使用
            releaseService.cancel(r2, "cancel-r2-" + round);
        }
        assertThat(quarantineFirst + pullFirst).isEqualTo(10);
    }

    /**
     * 并发解除：同一设备同一轮次多个不同操作人同时解除，恰有一个成功，其余 409；
     * 解除记录只落一次，历史失败计数保持2。
     */
    @Test
    void 并发解除_恰一个生效且历史失败不清除() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            registerDevice(deviceId);
            long r1 = firstFailure(deviceId, "p1-" + round, "f1-" + round);
            jdbc.update("UPDATE release_order SET status = 'CANCELLED', active_model = NULL WHERE id = ?", r1);
            long r2 = createRelease("c2-" + round);
            long t2 = taskService.pull(deviceId, "p2-" + round).task().taskId();
            taskService.receipt(t2, new ReceiptRequest("f2-" + round, ReceiptResult.FAILED, "原因B", "op-b"));
            assertThat(roundRepository.findActive(deviceId, "2.0.0").orElseThrow().status())
                    .isEqualTo(RoundStatus.QUARANTINED);
            final int seq = round;

            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int idx = i;
                tasks.add(() -> quarantineService.release(deviceId,
                        new ReleaseQuarantineRequest("rel-" + seq + "-" + idx, "2.0.0",
                                "op-review-" + idx, "复核通过")));
            }
            List<Object> results = runConcurrently(tasks);

            long ok = results.stream().filter(r -> !(r instanceof Exception)).count();
            long conflicts = results.stream()
                    .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                    .count();
            assertThat(ok).as("第%d轮：仅一个解除生效", round).isEqualTo(1);
            assertThat(conflicts).isEqualTo(3);

            List<DeviceFailureRound> rounds = roundRepository.findByDevice(deviceId);
            assertThat(rounds).hasSize(1);
            DeviceFailureRound only = rounds.get(0);
            assertThat(only.status()).isEqualTo(RoundStatus.RELEASED);
            assertThat(only.consecutiveFailed()).isEqualTo(2);
            assertThat(only.firstTaskId()).isNotNull();
            assertThat(only.secondTaskId()).isEqualTo(t2);
            assertThat(only.releasedBy()).startsWith("op-review-");
            // 解除不影响发布单状态，终结本轮发布单释放型号活动槽位
            releaseService.cancel(r2, "cancel-r2-" + round);
        }
    }

    /**
     * 同 requestId 第二次失败回执并发重放：隔离依据只固化一次，计数不重复。
     */
    @Test
    void 并发同requestId第二次失败_只隔离一次() throws Exception {
        registerDevice("d1");
        long r1 = firstFailure("d1", "p1", "f1");
        jdbc.update("UPDATE release_order SET status = 'CANCELLED', active_model = NULL WHERE id = ?", r1);
        long r2 = createRelease("c2");
        long t2 = taskService.pull("d1", "p2").task().taskId();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receipt(t2,
                    new ReceiptRequest("f2-same", ReceiptResult.FAILED, "原因B", "op-b")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        assertThat(roundRepository.findByDevice("d1")).hasSize(1);
        DeviceFailureRound round = roundRepository.findActive("d1", "2.0.0").orElseThrow();
        assertThat(round.status()).isEqualTo(RoundStatus.QUARANTINED);
        assertThat(round.consecutiveFailed()).isEqualTo(2);
        assertThat(round.secondTaskId()).isEqualTo(t2);
        assertThat(taskRepository.findById(t2).orElseThrow().status().name()).isEqualTo("FAILED");
    }

    /**
     * 跨版本独立：2.0.0 隔离后，设备对 3.0.0 的失败计数独立开启，互不影响。
     */
    @Test
    void 并发跨版本失败_计数与隔离互相独立() throws Exception {
        registerDevice("d1");
        long r1 = firstFailure("d1", "p1", "f1");
        jdbc.update("UPDATE release_order SET status = 'CANCELLED', active_model = NULL WHERE id = ?", r1);
        long r2 = createRelease("c2");
        long t2 = taskService.pull("d1", "p2").task().taskId();
        taskService.receipt(t2, new ReceiptRequest("f2", ReceiptResult.FAILED, "原因B", "op-b"));
        assertThat(roundRepository.findActive("d1", "2.0.0").orElseThrow().status())
                .isEqualTo(RoundStatus.QUARANTINED);

        // 另起 3.0.0 发布单：第一次失败建立独立 ACTIVE 轮次，不被 2.0.0 隔离影响
        releaseService.cancel(r2, "cancel-r2");
        long r3 = releaseService.create(
                new CreateReleaseRequest("c3", "m1", "1.0.0", "3.0.0", 100)).releaseId();
        long t3 = taskService.pull("d1", "p3").task().taskId();
        taskService.receipt(t3, new ReceiptRequest("f3", ReceiptResult.FAILED, "原因C", "op-a"));

        List<DeviceFailureRound> rounds = roundRepository.findByDevice("d1");
        assertThat(rounds).hasSize(2);
        assertThat(rounds.get(0).toVersion()).isEqualTo("2.0.0");
        assertThat(rounds.get(0).status()).isEqualTo(RoundStatus.QUARANTINED);
        assertThat(rounds.get(1).toVersion()).isEqualTo("3.0.0");
        assertThat(rounds.get(1).status()).isEqualTo(RoundStatus.ACTIVE);
        assertThat(rounds.get(1).consecutiveFailed()).isEqualTo(1);

        // 解除 2.0.0 不改变 3.0.0 的计数
        quarantineService.release("d1",
                new ReleaseQuarantineRequest("rel", "2.0.0", "op-c", "复核通过"));
        DeviceFailureRound round3 = roundRepository.findActive("d1", "3.0.0").orElseThrow();
        assertThat(round3.status()).isEqualTo(RoundStatus.ACTIVE);
        assertThat(round3.consecutiveFailed()).isEqualTo(1);
        assertThat(roundRepository.findByDevice("d1").get(0).status()).isEqualTo(RoundStatus.RELEASED);
    }
}
