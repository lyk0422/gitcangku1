package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
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
 * 自动暂停与人工恢复的并发边界测试：真实并发打到 H2 事务、行锁与唯一约束上。
 */
@SpringBootTest
class FirmwarePauseConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PauseRecordRepository pauseRecordRepository;

    @Autowired
    private ResumeRecordRepository resumeRecordRepository;

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
        executor = Executors.newFixedThreadPool(10);
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

    private long createRelease(String model, int sampleFloor, int threshold) {
        return releaseService.create(new CreateReleaseRequest("req-r-" + model, model, "1.0.0", "2.0.0",
                100, sampleFloor, threshold)).releaseId();
    }

    private long pullTask(String deviceId, String requestId) {
        return taskService.pull(deviceId, requestId).task().taskId();
    }

    @Test
    void 并发回执_最多一条暂停记录_统计不丢失() throws Exception {
        int devices = 8;
        for (int i = 0; i < devices; i++) {
            deviceService.register(new RegisterDeviceRequest("req-d" + i, "d" + i, "m1", "1.0.0", i));
        }
        long releaseId = createRelease("m1", 2, 1);
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            taskIds.add(pullTask("d" + i, "req-p" + i));
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            long taskId = taskIds.get(i);
            tasks.add(() -> taskService.receipt(taskId, new ReceiptRequest("req-rc" + taskId, ReceiptResult.FAILED)));
        }
        List<Object> results = runConcurrently(tasks);

        // 全部回执成功（PAUSED 不影响已有任务回执）
        assertThat(results).allMatch(r -> !(r instanceof Exception));
        // 最终状态 PAUSED，且全表最多一条暂停记录
        assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.PAUSED);
        assertThat(pauseRecordRepository.findByRelease(releaseId)).hasSize(1);
        // 统计不丢失：8 个失败样本全部计入
        var stats = taskRepository.statsForRound(releaseId, 1);
        assertThat(stats.failureCount()).isEqualTo(devices);
        assertThat(stats.sampleCount()).isEqualTo(devices);
        // 暂停记录保存了触发时刻的成功/失败数与触发任务
        var record = pauseRecordRepository.findByRelease(releaseId).get(0);
        assertThat(record.monitorRound()).isEqualTo(1);
        assertThat(record.failureCount()).isBetween(2, devices);
        assertThat(taskIds).contains(record.triggerTaskId());
        assertThat(record.pausedAt()).isNotNull();
    }

    @Test
    void 恢复与取消竞争_按提交顺序产生唯一合法状态() throws Exception {
        int resumeWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d0-" + round, "d0-" + round, model, "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("req-d1-" + round, "d1-" + round, model, "1.0.0", 2));
            long releaseId = createRelease(model, 2, 50);
            long t1 = pullTask("d0-" + round, "req-p0-" + round);
            long t2 = pullTask("d1-" + round, "req-p1-" + round);
            taskService.receipt(t1, new ReceiptRequest("req-rc0-" + round, ReceiptResult.FAILED));
            taskService.receipt(t2, new ReceiptRequest("req-rc1-" + round, ReceiptResult.FAILED));
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.PAUSED);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> releaseService.resume(releaseId,
                            new ResumeReleaseRequest("req-rs-" + seq, 1, "热修复已验证")),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x-" + seq)));

            Object resumeResult = results.get(0);
            var order = releaseService.findOrder(releaseId);
            // 取消最终必然生效（取消 PAUSED 或恢复后的 ACTIVE 都会转为 CANCELLED）
            assertThat(order.status()).isEqualTo(ReleaseStatus.CANCELLED);
            if (resumeResult instanceof ApiException ae) {
                // 取消先提交：恢复 409，版本与轮次不变，无恢复记录
                cancelWins++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(order.version()).isEqualTo(1);
                assertThat(order.monitorRound()).isEqualTo(1);
                assertThat(resumeRecordRepository.findByRelease(releaseId)).isEmpty();
            } else {
                // 恢复先提交：版本加一、轮次加一，随后取消仍生效
                resumeWins++;
                assertThat(order.version()).isEqualTo(2);
                assertThat(order.monitorRound()).isEqualTo(2);
                assertThat(resumeRecordRepository.findByRelease(releaseId)).hasSize(1);
            }
            // 历史暂停记录不可改
            assertThat(pauseRecordRepository.findByRelease(releaseId)).hasSize(1);
        }
        assertThat(resumeWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 恢复与回执竞争_回执计入完成时所在轮次() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d0-" + round, "d0-" + round, model, "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("req-d1-" + round, "d1-" + round, model, "1.0.0", 2));
            deviceService.register(new RegisterDeviceRequest("req-d2-" + round, "d2-" + round, model, "1.0.0", 3));
            long releaseId = createRelease(model, 2, 50);
            long t1 = pullTask("d0-" + round, "req-p0-" + round);
            long t2 = pullTask("d1-" + round, "req-p1-" + round);
            long t3 = pullTask("d2-" + round, "req-p2-" + round);
            taskService.receipt(t1, new ReceiptRequest("req-rc0-" + round, ReceiptResult.FAILED));
            taskService.receipt(t2, new ReceiptRequest("req-rc1-" + round, ReceiptResult.FAILED));
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.PAUSED);
            final int seq = round;

            // t3 仍为 PENDING：与恢复并发，回执计入其完成时所在轮次
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> releaseService.resume(releaseId,
                            new ResumeReleaseRequest("req-rs-" + seq, 1, "热修复已验证")),
                    (Callable<Object>) () -> taskService.receipt(t3,
                            new ReceiptRequest("req-rc2-" + seq, ReceiptResult.FAILED))));

            // 恢复一定成功（发布单处于 PAUSED），回执一定成功（任务 PENDING 可终结）
            assertThat(results).allMatch(r -> !(r instanceof Exception));
            var order = releaseService.findOrder(releaseId);
            assertThat(order.status()).isEqualTo(ReleaseStatus.ACTIVE);
            assertThat(order.monitorRound()).isEqualTo(2);

            Integer firstResultRound = jdbc.queryForObject(
                    "SELECT first_result_round FROM rollout_task WHERE id = ?", Integer.class, t3);
            assertThat(firstResultRound).isNotNull();
            if (firstResultRound == 1) {
                // 回执先提交：计入第 1 轮，新轮统计从零开始
                assertThat(taskRepository.statsForRound(releaseId, 1).failureCount()).isEqualTo(3);
                assertThat(taskRepository.statsForRound(releaseId, 2).sampleCount()).isZero();
            } else {
                // 恢复先提交：回执只计入第 2 轮
                assertThat(firstResultRound).isEqualTo(2);
                assertThat(taskRepository.statsForRound(releaseId, 1).failureCount()).isEqualTo(2);
                assertThat(taskRepository.statsForRound(releaseId, 2).failureCount()).isEqualTo(1);
            }
            assertThat(resumeRecordRepository.findByRelease(releaseId)).hasSize(1);
        }
    }
}
