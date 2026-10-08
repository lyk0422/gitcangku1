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
import com.example.starter.incident.dto.Requests.PlanStepDefinition;
import com.example.starter.incident.dto.Requests.PlanStepExecuteRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.PlanView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指令计划并发测试（真实 H2 内存库，MODE=MySQL）：
 * 验证执行与取消在事件行锁下串行化、取消提交后绝不新增效果；
 * 同键并发执行只记一次效果；同一步骤并发补偿仅一次成功。
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
        jdbc.update("DELETE FROM command_plan_compensations");
        jdbc.update("DELETE FROM command_plan_steps");
        jdbc.update("DELETE FROM command_plans");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private static String rid() {
        return "REQ-" + UUID.randomUUID();
    }

    private static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
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

    private PlanView threeStepPlanWithTwoApplied(String incidentKey) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发计划", "r"));
        incidentService.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        PlanView plan = planService.createPlan(incidentKey, "alice", rid(),
                new PlanCreateRequest(key(), List.of(
                        new PlanStepDefinition("S1", "e1", "c1"),
                        new PlanStepDefinition("S2", "e2", "c2"),
                        new PlanStepDefinition("S3", "e3", "c3"))));
        planService.executeStep(incidentKey, plan.planId(), "S1", "alice", rid(),
                new PlanStepExecuteRequest(key(), null));
        planService.executeStep(incidentKey, plan.planId(), "S2", "alice", rid(),
                new PlanStepExecuteRequest(key(), null));
        return plan;
    }

    @Test
    void executeS3AndCancel_areSerialized_noEffectAfterCancel() throws Exception {
        // 重复若干轮以覆盖两种提交顺序
        for (int round = 0; round < 8; round++) {
            String ik = "INC-CXC-" + round;
            PlanView plan = threeStepPlanWithTwoApplied(ik);

            List<Object> results = runConcurrently(List.of(
                    () -> planService.executeStep(ik, plan.planId(), "S3", "alice", rid(),
                            new PlanStepExecuteRequest(key(), null)),
                    () -> planService.cancelPlan(ik, plan.planId(), "alice", rid(),
                            new PlanCancelRequest(key(), null))));

            Object executeResult = results.get(0);
            boolean executeWon = executeResult instanceof PlanView;
            boolean executeLost = executeResult instanceof ApiException a
                    && a.status().value() == 409;
            assertThat(executeWon || executeLost)
                    .as("执行 S3 要么先提交成功，要么被取消挡下 409").isTrue();

            PlanView finalView = planService.getPlan(ik, plan.planId());
            String s3Status = finalView.steps().get(2).status();
            if (executeWon) {
                // 执行先提交：S3 已 APPLIED，随后取消进入 COMPENSATING，逆序补偿首步为 S3
                assertThat(s3Status).isEqualTo("APPLIED");
                assertThat(finalView.status()).isEqualTo("COMPENSATING");
                assertThat(finalView.compensationStepKey()).isEqualTo("S3");
            } else {
                // 取消先提交：S3 永不执行，取消后不会新增任何效果
                assertThat(s3Status).isEqualTo("PENDING");
                assertThat(finalView.status()).isEqualTo("COMPENSATING");
                assertThat(finalView.compensationStepKey()).isEqualTo("S2");
                // 取消后再执行 S3 仍被拒绝，数据保持
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> planService
                        .executeStep(ik, plan.planId(), "S3", "alice", rid(),
                                new PlanStepExecuteRequest(key(), null))))
                        .isInstanceOf(ApiException.class);
                assertThat(planService.getPlan(ik, plan.planId()).steps().get(2).status())
                        .isEqualTo("PENDING");
            }
            // APPLIED 步骤数与补偿顺序严格一致：已 APPLIED 集合为 {S1,S2} 或 {S1,S2,S3}
            long appliedCount = finalView.steps().stream()
                    .filter(s -> s.status().equals("APPLIED")).count();
            assertThat(appliedCount).isEqualTo(executeWon ? 3 : 2);
        }
    }

    @Test
    void concurrentSameCommandKeyExecute_singleEffect() throws Exception {
        String ik = "INC-CXC-SAMEKEY";
        PlanView plan = threeStepPlanWithTwoApplied(ik);
        String sharedKey = key();
        List<Object> results = runConcurrently(List.of(
                () -> planService.executeStep(ik, plan.planId(), "S3", "alice", "REQ-SAME",
                        new PlanStepExecuteRequest(sharedKey, null)),
                () -> planService.executeStep(ik, plan.planId(), "S3", "alice", "REQ-SAME",
                        new PlanStepExecuteRequest(sharedKey, null)),
                () -> planService.executeStep(ik, plan.planId(), "S3", "alice", "REQ-SAME",
                        new PlanStepExecuteRequest(sharedKey, null))));

        List<PlanView> views = results.stream().filter(PlanView.class::isInstance)
                .map(PlanView.class::cast).toList();
        assertThat(views).isNotEmpty();
        assertThat(views).allSatisfy(v -> assertThat(v).isEqualTo(views.get(0)));
        assertThat(planService.getPlan(ik, plan.planId()).steps().get(2).status())
                .isEqualTo("APPLIED");
        Integer appliedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_plan_steps WHERE plan_id = ? AND status = 'APPLIED'",
                Integer.class, plan.planId());
        assertThat(appliedRows).isEqualTo(3);
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, sharedKey);
        assertThat(keyRows).isEqualTo(1);
    }

    @Test
    void concurrentCompensateSameStep_onlyOneSucceeds() throws Exception {
        String ik = "INC-CXC-COMP";
        PlanView plan = threeStepPlanWithTwoApplied(ik);
        planService.cancelPlan(ik, plan.planId(), "alice", rid(),
                new PlanCancelRequest(key(), null));

        List<Object> results = runConcurrently(List.of(
                () -> planService.compensateStep(ik, plan.planId(), "S2", "alice", rid(),
                        new PlanCompensateRequest(key(), null, "回滚-1", null)),
                () -> planService.compensateStep(ik, plan.planId(), "S2", "alice", rid(),
                        new PlanCompensateRequest(key(), null, "回滚-2", null))));

        long successes = results.stream().filter(PlanView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(a -> a.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        PlanView after = planService.getPlan(ik, plan.planId());
        assertThat(after.steps().get(1).status()).isEqualTo("COMPENSATED");
        assertThat(after.compensationStepKey()).isEqualTo("S1");
        Long s2StepId = jdbc.queryForObject(
                "SELECT id FROM command_plan_steps WHERE plan_id = ? AND step_index = 2",
                Long.class, plan.planId());
        Integer compRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_plan_compensations WHERE step_id = ?"
                        + " AND status = 'SUCCEEDED'", Integer.class, s2StepId);
        assertThat(compRows).isEqualTo(1);
    }
}
