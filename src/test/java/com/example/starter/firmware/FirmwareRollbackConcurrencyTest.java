package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.CreateRollbackPlanRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ResumeRollbackPlanRequest;
import com.example.starter.firmware.api.RollbackDispatchRequest;
import com.example.starter.firmware.api.RollbackDispatchResponse;
import com.example.starter.firmware.api.RollbackHopTaskView;
import com.example.starter.firmware.api.RollbackReceiptRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RollbackPlanStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.RollbackHopTaskRepository;
import com.example.starter.firmware.repo.RollbackPlanRepository;
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
 * 多跳回退并发与幂等边界测试：真实并发打到 H2 事务与唯一约束上。
 */
@SpringBootTest
class FirmwareRollbackConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RollbackService rollbackService;

    @Autowired
    private RollbackPlanRepository planRepository;

    @Autowired
    private RollbackHopTaskRepository hopTaskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollback_plan_pause_record");
        jdbc.update("DELETE FROM rollback_hop_task");
        jdbc.update("DELETE FROM rollback_plan_hop");
        jdbc.update("DELETE FROM rollback_plan_device");
        jdbc.update("DELETE FROM rollback_plan");
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

    /**
     * 让设备完成一次 from->to 投放并取消发布单，返回发布单ID。
     */
    private long completeRollout(String keyPrefix, String deviceId, String model,
                                 String from, String to) {
        long releaseId = releaseService.create(
                new CreateReleaseRequest(keyPrefix + "-rel", model, from, to, 100)).releaseId();
        long taskId = taskService.pull(deviceId, keyPrefix + "-pull").task().taskId();
        taskService.receipt(taskId, new ReceiptRequest(keyPrefix + "-rc", ReceiptResult.SUCCESS));
        releaseService.cancel(releaseId, keyPrefix + "-cancel");
        return releaseId;
    }

    @Test
    void 并发创建同planKey_至多一张计划() throws Exception {
        deviceService.register(new RegisterDeviceRequest("r-d1", "d1", "m1", "1.0.0", 1));
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            tasks.add(() -> rollbackService.create(new CreateRollbackPlanRequest(
                    "r-c" + seq, "pk-same", rel, "1.0.0", List.of("d1"), 2, 50)).planId());
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发创建不同planKey同一设备_设备至多进入一张计划() throws Exception {
        deviceService.register(new RegisterDeviceRequest("r-d1", "d1", "m1", "1.0.0", 1));
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            tasks.add(() -> rollbackService.create(new CreateRollbackPlanRequest(
                    "r-c" + seq, "pk-" + seq, rel, "1.0.0", List.of("d1"), 2, 50)).planId());
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && "DEVICE_BUSY".equals(ae.code()))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        Long occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_plan_device WHERE active_device_id = 'd1'", Long.class);
        assertThat(occupied).isEqualTo(1);
    }

    @Test
    void 并发派发_同设备同跳同轮次至多一条任务() throws Exception {
        deviceService.register(new RegisterDeviceRequest("r-d1", "d1", "m1", "1.0.0", 1));
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");
        long planId = rollbackService.create(new CreateRollbackPlanRequest(
                "r-c", "pk-1", rel, "1.0.0", List.of("d1"), 2, 50)).planId();

        int threads = 8;
        List<Callable<RollbackDispatchResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> rollbackService.dispatch(planId,
                    new RollbackDispatchRequest("r-disp" + seq, "d1")));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        List<String> receiptKeys = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(RollbackDispatchResponse.class);
            RollbackHopTaskView task = ((RollbackDispatchResponse) result).hopTask();
            assertThat(task).isNotNull();
            taskIds.add(task.hopTaskId());
            receiptKeys.add(task.receiptKey());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(receiptKeys).allMatch(key -> key.equals(receiptKeys.get(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_hop_task", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发回执与取消_一致提交顺序且已成功不回滚() throws Exception {
        int successWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("r-d" + round, deviceId, model, "1.0.0", 1));
            long rel = completeRollout("k" + round, deviceId, model, "1.0.0", "2.0.0");
            long planId = rollbackService.create(new CreateRollbackPlanRequest(
                    "r-c" + round, "pk-" + round, rel, "1.0.0", List.of(deviceId), 2, 50)).planId();
            RollbackHopTaskView task = rollbackService.dispatch(planId,
                    new RollbackDispatchRequest("r-disp" + round, deviceId)).hopTask();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> rollbackService.receipt(task.hopTaskId(),
                            new RollbackReceiptRequest("r-rc" + seq, task.receiptKey(),
                                    ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> rollbackService.cancel(planId, "r-cancel" + seq)));

            Object receiptResult = results.get(0);
            var finalTask = hopTaskRepository.findById(task.hopTaskId()).orElseThrow();
            String deviceVersion = deviceService.get(deviceId).currentVersion();
            if (finalTask.status().name().equals("SUCCESS")) {
                successWins++;
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
                assertThat(deviceVersion).isEqualTo("1.0.0");
                // 全部设备到达目标：计划完结
                assertThat(planRepository.findById(planId).orElseThrow().status())
                        .isEqualTo(RollbackPlanStatus.COMPLETED);
            } else {
                cancelWins++;
                assertThat(finalTask.status().name()).isEqualTo("CANCELLED");
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(deviceVersion).isEqualTo("2.0.0");
                assertThat(planRepository.findById(planId).orElseThrow().status())
                        .isEqualTo(RollbackPlanStatus.CANCELLED);
            }
        }
        assertThat(successWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 并发失败回执_统计不丢失且每轮至多一条暂停记录() throws Exception {
        int devices = 6;
        long rel = 0;
        for (int i = 0; i < devices; i++) {
            deviceService.register(new RegisterDeviceRequest("r-d" + i, "d" + i, "m1", "1.0.0", i));
            rel = completeRollout("k" + i, "d" + i, "m1", "1.0.0", "2.0.0");
        }
        List<String> deviceIds = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            deviceIds.add("d" + i);
        }
        long planId = rollbackService.create(new CreateRollbackPlanRequest(
                "r-c", "pk-1", rel, "1.0.0", deviceIds, 2, 50)).planId();

        List<RollbackHopTaskView> hopTasks = new ArrayList<>();
        for (String deviceId : deviceIds) {
            hopTasks.add(rollbackService.dispatch(planId,
                    new RollbackDispatchRequest("r-disp-" + deviceId, deviceId)).hopTask());
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (RollbackHopTaskView hopTask : hopTasks) {
            tasks.add(() -> rollbackService.receipt(hopTask.hopTaskId(),
                    new RollbackReceiptRequest("r-rc-" + hopTask.hopTaskId(), hopTask.receiptKey(),
                            ReceiptResult.FAILED)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        var plan = planRepository.findById(planId).orElseThrow();
        assertThat(plan.status()).isEqualTo(RollbackPlanStatus.PAUSED);
        assertThat(plan.roundFailed()).isEqualTo(devices);
        assertThat(plan.pausedHopIndex()).isEqualTo(1);
        Long pauses = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_plan_pause_record WHERE plan_id = ?", Long.class, planId);
        assertThat(pauses).isEqualTo(1);
    }

    @Test
    void 并发恢复与取消_按提交顺序产生唯一合法状态() throws Exception {
        int cancelFirst = 0;
        int resumeFirst = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("r-d" + round, deviceId, model, "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("r-e" + round, "e" + round, model, "1.0.0", 2));
            long rel = completeRollout("k" + round, deviceId, model, "1.0.0", "2.0.0");
            completeRollout("ke" + round, "e" + round, model, "1.0.0", "2.0.0");
            long planId = rollbackService.create(new CreateRollbackPlanRequest(
                    "r-c" + round, "pk-" + round, rel, "1.0.0", List.of(deviceId, "e" + round), 2, 50))
                    .planId();
            // 两台设备该跳均失败，触发暂停
            RollbackHopTaskView t1 = rollbackService.dispatch(planId,
                    new RollbackDispatchRequest("r-disp1-" + round, deviceId)).hopTask();
            RollbackHopTaskView t2 = rollbackService.dispatch(planId,
                    new RollbackDispatchRequest("r-disp2-" + round, "e" + round)).hopTask();
            rollbackService.receipt(t1.hopTaskId(),
                    new RollbackReceiptRequest("r-f1-" + round, t1.receiptKey(), ReceiptResult.FAILED));
            rollbackService.receipt(t2.hopTaskId(),
                    new RollbackReceiptRequest("r-f2-" + round, t2.receiptKey(), ReceiptResult.FAILED));
            assertThat(planRepository.findById(planId).orElseThrow().status())
                    .isEqualTo(RollbackPlanStatus.PAUSED);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> rollbackService.resume(planId,
                            new ResumeRollbackPlanRequest("r-res" + seq, "修复完成")),
                    (Callable<Object>) () -> rollbackService.cancel(planId, "r-can" + seq)));

            Object resumeResult = results.get(0);
            assertThat(results.get(1)).as("取消对既有计划始终成功").isNotInstanceOf(Exception.class);
            var finalPlan = planRepository.findById(planId).orElseThrow();
            assertThat(finalPlan.status()).isEqualTo(RollbackPlanStatus.CANCELLED);
            if (resumeResult instanceof ApiException ae) {
                cancelFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(finalPlan.monitorRound()).isEqualTo(1);
            } else {
                resumeFirst++;
                assertThat(finalPlan.monitorRound()).isEqualTo(2);
            }
        }
        assertThat(cancelFirst + resumeFirst).isEqualTo(10);
    }

    @Test
    void 并发同requestId创建_重放一致且副作用只发生一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("r-d1", "d1", "m1", "1.0.0", 1));
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> rollbackService.create(new CreateRollbackPlanRequest(
                    "r-same", "pk-1", rel, "1.0.0", List.of("d1"), 2, 50)).planId());
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(results).allMatch(r -> r.equals(results.get(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan_device", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan_hop", Long.class))
                .isEqualTo(1);
    }

    @Test
    void 并发回退创建与投放拉取_设备不进入冲突任务() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("r-d" + round, deviceId, model, "1.0.0", 1));
            long rel = completeRollout("k" + round, deviceId, model, "1.0.0", "2.0.0");
            // 新的 ACTIVE 投放等待设备拉取
            long newRelease = releaseService.create(
                    new CreateReleaseRequest("r-rel" + round, model, "2.0.0", "3.0.0", 100)).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> rollbackService.create(new CreateRollbackPlanRequest(
                            "r-c" + seq, "pk-" + seq, rel, "1.0.0", List.of(deviceId), 2, 50)).planId(),
                    (Callable<Object>) () -> taskService.pull(deviceId, "r-pull" + seq)));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            Long pendingRollout = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_id = ? AND status = 'PENDING'",
                    Long.class, deviceId);
            Long occupied = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollback_plan_device WHERE active_device_id = ?",
                    Long.class, deviceId);
            // 设备不得同时持有未终结投放任务与未终结回退计划占用
            assertThat(pendingRollout + occupied)
                    .as("第%d轮：设备不得同时进入冲突任务", round)
                    .isLessThanOrEqualTo(1);
            releaseService.cancel(newRelease, "r-clean" + round);
        }
    }
}
