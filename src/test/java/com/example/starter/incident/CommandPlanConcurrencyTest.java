package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.PlanCancelRequest;
import com.example.starter.incident.dto.Requests.PlanCompensateRequest;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanExecuteRequest;
import com.example.starter.incident.dto.Requests.PlanStepInput;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.PlanView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指令计划并发边界测试：验证同 requestId 并发单次生效、
 * 执行与取消竞争不出现取消后新增效果、并发补偿按逆序串行。
 */
@SpringBootTest
class CommandPlanConcurrencyTest {

    @Autowired
    private CommandPlanService planService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM command_plan_compensations");
        jdbc.update("DELETE FROM command_plan_steps");
        jdbc.update("DELETE FROM command_plans");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTwoStepPlan(String incidentKey, String planKey) {
        planService.createPlan(incidentKey, "alice",
                new PlanCreateRequest(key(), planKey, List.of(
                        new PlanStepInput("S1", "效果1", "补偿1"),
                        new PlanStepInput("S2", "效果2", "补偿2"))));
    }

    /**
     * 并发提交一批任务并收集结果（成功值或异常）。
     */
    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
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
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentExecute_sameRequestId_singleEffect() throws Exception {
        commanding("INC-CC1", "alice");
        createTwoStepPlan("INC-CC1", "PLAN-1");
        String requestId = key();
        List<Callable<PlanView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> planService.executeStep("INC-CC1", "PLAN-1", "S1", "alice",
                    new PlanExecuteRequest(requestId, 1L)));
        }
        List<Object> results = runConcurrently(tasks);

        List<PlanView> successes = results.stream().filter(PlanView.class::isInstance)
                .map(PlanView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且步骤只执行一次（版本只递增一次）
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        PlanView end = planService.getPlan("INC-CC1", "PLAN-1");
        assertThat(end.steps().get(0).status()).isEqualTo("APPLIED");
        assertThat(end.version()).isEqualTo(2);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, requestId);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentExecuteAndCancel_noEffectAfterCancel() throws Exception {
        commanding("INC-CC2", "alice");
        createTwoStepPlan("INC-CC2", "PLAN-1");
        // 并发：执行 S1 与取消计划
        List<Object> results = runConcurrently(List.of(
                () -> planService.executeStep("INC-CC2", "PLAN-1", "S1", "alice",
                        new PlanExecuteRequest(key(), 1L)),
                () -> planService.cancelPlan("INC-CC2", "PLAN-1", "alice",
                        new PlanCancelRequest(key()))));

        PlanView end = planService.getPlan("INC-CC2", "PLAN-1");
        boolean s1Applied = end.steps().get(0).status().equals("APPLIED");
        if (s1Applied) {
            // 执行先提交：S1 已执行，取消后进入 COMPENSATING（待补偿）
            assertThat(end.status()).isEqualTo("COMPENSATING");
            assertThat(results.get(0)).isInstanceOf(PlanView.class);
        } else {
            // 取消先提交：无已执行步骤直接 CANCELLED，执行被拒绝（不出现取消后新增效果）
            assertThat(end.status()).isEqualTo("CANCELLED");
            assertThat(end.steps().get(0).status()).isEqualTo("PENDING");
            assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Test
    void concurrentCompensate_reverseOrderSerialized() throws Exception {
        commanding("INC-CC3", "alice");
        createTwoStepPlan("INC-CC3", "PLAN-1");
        planService.executeStep("INC-CC3", "PLAN-1", "S1", "alice", new PlanExecuteRequest(key(), 1L));
        planService.executeStep("INC-CC3", "PLAN-1", "S2", "alice", new PlanExecuteRequest(key(), 2L));
        planService.cancelPlan("INC-CC3", "PLAN-1", "alice", new PlanCancelRequest(key()));
        // 并发：正确逆序补偿 S2 与跳步补偿 S1（当前应先补偿 S2）
        List<Object> results = runConcurrently(List.of(
                () -> planService.compensateStep("INC-CC3", "PLAN-1", "S2", "alice",
                        new PlanCompensateRequest(key(), "补偿S2", 4L)),
                () -> planService.compensateStep("INC-CC3", "PLAN-1", "S1", "alice",
                        new PlanCompensateRequest(key(), "补偿S1", 4L))));

        // 无论提交顺序如何，S2（正确逆序步骤）补偿必然成功
        assertThat(results.get(0)).isInstanceOf(PlanView.class);
        PlanView end = planService.getPlan("INC-CC3", "PLAN-1");
        assertThat(end.steps().get(1).status()).isEqualTo("COMPENSATED");
        // S1 在 S2 补偿前是跳步（409），在 S2 补偿后是版本不符的补偿失败（200 记录诊断）；
        // 两种情况下 S1 都保持 APPLIED，计划仍 COMPENSATING，不会越序补偿
        assertThat(end.steps().get(0).status()).isEqualTo("APPLIED");
        assertThat(end.status()).isEqualTo("COMPENSATING");
        Object s1Result = results.get(1);
        if (s1Result instanceof ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        } else {
            PlanView failed = (PlanView) s1Result;
            assertThat(failed.compensations())
                    .anySatisfy(c -> assertThat(c.outcome()).isEqualTo("FAILED"));
        }
    }
}
