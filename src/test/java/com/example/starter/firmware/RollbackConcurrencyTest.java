package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.CreateRollbackPlanRequest;
import com.example.starter.firmware.api.DispatchRollbackRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.ReceiptRollbackRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ResumeRollbackPlanRequest;
import com.example.starter.firmware.api.RollbackDispatchResponse;
import com.example.starter.firmware.api.RollbackPlanView;
import com.example.starter.firmware.api.RollbackTaskView;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RollbackPlanStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.RollbackService;
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
 * 多跳回退并发与幂等边界测试：真实并发打到 H2 行锁、唯一约束与设备占用表，
 * 验证回退创建/回执/恢复与正向投放互斥、统计不丢失与一致提交顺序。
 */
@SpringBootTest
class RollbackConcurrencyTest {

    @Autowired
    private RollbackService rollbackService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollback_task");
        jdbc.update("DELETE FROM rollback_plan_hop");
        jdbc.update("DELETE FROM rollback_plan");
        jdbc.update("DELETE FROM device_task_occupation");
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

    private long successfulCancelledRelease(String prefix, String model, String from, String to,
                                            List<String> devices) {
        long releaseId = releaseService.create(
                new CreateReleaseRequest(prefix + "-c", model, from, to, 100, 2, 100)).releaseId();
        for (String deviceId : devices) {
            PullResponse pulled = taskService.pull(deviceId, prefix + "-p-" + deviceId);
            assertThat(pulled.task()).isNotNull();
            taskService.receipt(pulled.task().taskId(),
                    new ReceiptRequest(prefix + "-r-" + deviceId, ReceiptResult.SUCCESS));
        }
        releaseService.cancel(releaseId, prefix + "-x");
        return releaseId;
    }

    private RollbackTaskView dispatch(long planId, String requestId, String deviceId) {
        return rollbackService.dispatch(planId, new DispatchRollbackRequest(requestId, deviceId)).task();
    }

    @Test
    void 并发创建回退计划与正向拉取_设备至多进入一个冲突任务() throws Exception {
        int rollbackWins = 0;
        int forwardWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("reg-" + round, deviceId, model, "v1", 1));
            // 旧单 v1→v2 成功并取消，构成回退历史；设备当前 v2，占用已释放
            long oldRelease = successfulCancelledRelease("old" + round, model, "v1", "v2", List.of(deviceId));
            // 新 ACTIVE 单 v2→v3，设备满足正向派发条件
            long newRelease = releaseService.create(
                    new CreateReleaseRequest("new" + round + "-c", model, "v2", "v3", 100)).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                            "rb" + seq, "plan" + seq, oldRelease, "v1", List.of(deviceId), null, null)),
                    (Callable<Object>) () -> taskService.pull(deviceId, "pull" + seq)));

            Object rbResult = results.get(0);
            Object pullResult = results.get(1);

            // 设备占用始终至多一条，且不存在回退计划与正向 PENDING 任务并存
            Long occupations = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM device_task_occupation WHERE device_id = ?", Long.class, deviceId);
            assertThat(occupations).as("第%d轮设备占用数", round).isEqualTo(1);
            Long pendingForward = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status = 'PENDING'",
                    Long.class, newRelease);
            Long planCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollback_plan WHERE source_release_id = ?", Long.class, oldRelease);

            if (rbResult instanceof RollbackPlanView) {
                rollbackWins++;
                assertThat(pullResult).isInstanceOfSatisfying(PullResponse.class,
                        pr -> assertThat(pr.task()).isNull());
                assertThat(pendingForward).isZero();
                assertThat(planCount).isEqualTo(1);
            } else {
                forwardWins++;
                // 回退创建因设备被占用而 409
                assertThat(rbResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("DEVICE_BUSY");
                });
                assertThat(pullResult).isInstanceOf(PullResponse.class);
                assertThat(((PullResponse) pullResult).task()).isNotNull();
                assertThat(pendingForward).isEqualTo(1);
                assertThat(planCount).isZero();
            }
        }
        assertThat(rollbackWins + forwardWins).isEqualTo(10);
    }

    @Test
    void 并发同requestId创建计划_重放一致且路径只构造一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("reg", "d1", "m1", "v1", 1));
        long releaseId = successfulCancelledRelease("rel", "m1", "v1", "v2", List.of("d1"));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> rollbackService.createPlan(new CreateRollbackPlanRequest(
                    "rb-same", "plan-1", releaseId, "v1", List.of("d1"), null, null)));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan_hop", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_task_occupation", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发同requestId回执_只终结一次_版本与统计只生效一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("reg", "d1", "m1", "v1", 1));
        long releaseId = successfulCancelledRelease("rel", "m1", "v1", "v2", List.of("d1"));
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb", "plan-1", releaseId, "v1", List.of("d1"), null, null));
        RollbackTaskView task = dispatch(plan.planId(), "disp", "d1");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> rollbackService.receipt(task.taskId(),
                    new ReceiptRollbackRequest("rc-same", "key-same", ReceiptResult.SUCCESS)));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        // 版本只切换一次（v2→v1），计划恰好完成、占用释放
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("v1");
        assertThat(rollbackService.findPlan(plan.planId()).status()).isEqualTo(RollbackPlanStatus.COMPLETED);
        assertThat(rollbackService.findPlan(plan.planId()).roundSuccess()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_task WHERE status = 'SUCCESS'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_task_occupation", Long.class)).isZero();
    }

    @Test
    void 并发多设备成功回执_跳统计不丢失_全部成功原子推进到COMPLETED() throws Exception {
        int devices = 6;
        for (int i = 0; i < devices; i++) {
            deviceService.register(new RegisterDeviceRequest("reg" + i, "d" + i, "m1", "v1", i));
        }
        long releaseId = successfulCancelledRelease("rel", "m1", "v1", "v2",
                java.util.stream.IntStream.range(0, devices).mapToObj(i -> "d" + i).toList());
        RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                "rb", "plan-1", releaseId, "v1",
                java.util.stream.IntStream.range(0, devices).mapToObj(i -> "d" + i).toList(),
                devices, 100));
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            taskIds.add(dispatch(plan.planId(), "p" + i, "d" + i).taskId());
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            long taskId = taskIds.get(i);
            int idx = i;
            tasks.add(() -> rollbackService.receipt(taskId,
                    new ReceiptRollbackRequest("r" + idx, "key" + idx, ReceiptResult.SUCCESS)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        var finished = rollbackService.findPlan(plan.planId());
        assertThat(finished.status()).isEqualTo(RollbackPlanStatus.COMPLETED);
        assertThat(finished.roundSuccess()).isEqualTo(devices);
        for (int i = 0; i < devices; i++) {
            assertThat(deviceService.get("d" + i).currentVersion()).isEqualTo("v1");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_task_occupation", Long.class)).isZero();
    }

    @Test
    void 并发恢复与取消_按提交顺序产生唯一合法状态() throws Exception {
        int cancelFirst = 0;
        int resumeFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("reg" + round, deviceId, model, "v1", 1));
            deviceService.register(new RegisterDeviceRequest("reg2" + round, "e" + round, model, "v1", 2));
            List<String> both = List.of(deviceId, "e" + round);
            long releaseId = successfulCancelledRelease("rel" + round, model, "v1", "v2", both);
            RollbackPlanView plan = rollbackService.createPlan(new CreateRollbackPlanRequest(
                    "rb" + round, "plan" + round, releaseId, "v1", both, 2, 50));
            long t1 = dispatch(plan.planId(), "p1-" + round, deviceId).taskId();
            long t2 = dispatch(plan.planId(), "p2-" + round, "e" + round).taskId();
            rollbackService.receipt(t1, new ReceiptRollbackRequest("f1-" + round, "k1-" + round,
                    ReceiptResult.FAILED));
            rollbackService.receipt(t2, new ReceiptRollbackRequest("f2-" + round, "k2-" + round,
                    ReceiptResult.FAILED));
            assertThat(rollbackService.findPlan(plan.planId()).status()).isEqualTo(RollbackPlanStatus.PAUSED);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> rollbackService.resume(plan.planId(),
                            new ResumeRollbackPlanRequest("rs" + seq, "修复")),
                    (Callable<Object>) () -> rollbackService.cancel(plan.planId(), "cx" + seq)));

            Object resumeResult = results.get(0);
            assertThat(results.get(1)).as("取消始终成功").isNotInstanceOf(Exception.class);
            assertThat(rollbackService.findPlan(plan.planId()).status())
                    .isEqualTo(RollbackPlanStatus.CANCELLED);
            if (resumeResult instanceof ApiException ae) {
                cancelFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("PLAN_CANCELLED");
                assertThat(rollbackService.findPlan(plan.planId()).currentRound()).isEqualTo(1);
            } else {
                resumeFirst++;
                assertThat(resumeResult).isNotInstanceOf(Exception.class);
                // 恢复先提交则轮次已加一，随后取消终结；恢复预派发的新 round PENDING 任务一并取消
                assertThat(rollbackService.findPlan(plan.planId()).currentRound()).isEqualTo(2);
                Long pending = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rollback_task WHERE plan_id = ? AND status = 'PENDING'",
                        Long.class, plan.planId());
                assertThat(pending).isZero();
            }
        }
        assertThat(cancelFirst + resumeFirst).isEqualTo(10);
    }
}
