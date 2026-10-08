package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.PlanCancelRequest;
import com.example.starter.incident.dto.Requests.PlanCompensateRequest;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanStepDefinition;
import com.example.starter.incident.dto.Requests.PlanStepExecuteRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.PlanStepView;
import com.example.starter.incident.dto.Responses.PlanView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指令计划服务测试：创建与顺序执行、取消与逆序补偿、失败诊断与重试、
 * 跳步/重复执行、期望版本、指挥权 403、幂等同键重放/异参冲突、解决门禁。
 */
@SpringBootTest
class CommandPlanServiceTest {

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

    private static PlanStepDefinition step(String stepKey) {
        return new PlanStepDefinition(stepKey, "合成效果-" + stepKey, "补偿说明-" + stepKey);
    }

    private String commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "联动处置", "r"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
        return incidentKey;
    }

    private PlanView createThreeStepPlan(String incidentKey, String commander) {
        PlanCreateRequest req = new PlanCreateRequest(key(),
                List.of(step("S1"), step("S2"), step("S3")));
        PlanView plan = planService.createPlan(incidentKey, commander, rid(), req);
        assertThat(plan.status()).isEqualTo("RUNNING");
        assertThat(plan.version()).isZero();
        assertThat(plan.nextStepKey()).isEqualTo("S1");
        assertThat(plan.compensationStepKey()).isNull();
        assertThat(plan.steps()).extracting(PlanStepView::stepKey)
                .containsExactly("S1", "S2", "S3");
        assertThat(plan.steps()).extracting(PlanStepView::status)
                .containsExactly("PENDING", "PENDING", "PENDING");
        return plan;
    }

    private PlanView execute(PlanView plan, String commander, String stepKey) {
        return planService.executeStep(plan.incidentKey(), plan.planId(), stepKey, commander,
                rid(), new PlanStepExecuteRequest(key(), null));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void createAndExecuteInOrder_runningUntilAllApplied() {
        String ik = commanding("INC-PLAN-1", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");

        PlanView afterS1 = execute(plan, "alice", "S1");
        assertThat(afterS1.status()).isEqualTo("RUNNING");
        assertThat(afterS1.version()).isEqualTo(1);
        assertThat(afterS1.nextStepKey()).isEqualTo("S2");
        assertThat(afterS1.steps().get(0).status()).isEqualTo("APPLIED");
        assertThat(afterS1.steps().get(0).appliedBy()).isEqualTo("alice");
        assertThat(afterS1.steps().get(0).appliedAt()).isNotNull();

        PlanView afterS2 = execute(plan, "alice", "S2");
        assertThat(afterS2.status()).isEqualTo("RUNNING");
        assertThat(afterS2.nextStepKey()).isEqualTo("S3");

        PlanView completed = execute(plan, "alice", "S3");
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.version()).isEqualTo(3);
        assertThat(completed.nextStepKey()).isNull();
        assertThat(completed.steps()).extracting(PlanStepView::status)
                .containsExactly("APPLIED", "APPLIED", "APPLIED");
    }

    @Test
    void skipStepAndReExecuteOldStep_areRejected() {
        String ik = commanding("INC-PLAN-2", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        execute(plan, "alice", "S1");

        // 跳步执行 S3：409
        assertApiStatus(() -> execute(plan, "alice", "S3"), HttpStatus.CONFLICT);
        // 再次执行已成功的旧步骤 S1：不重复记效果，409
        assertApiStatus(() -> execute(plan, "alice", "S1"), HttpStatus.CONFLICT);

        PlanView current = planService.getPlan(ik, plan.planId());
        assertThat(current.steps()).extracting(PlanStepView::status)
                .containsExactly("APPLIED", "PENDING", "PENDING");
    }

    @Test
    void cancelRunningThenCompensateInReverse_failureKeepsAppliedAndBlocksResolve() {
        String ik = commanding("INC-PLAN-3", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        execute(plan, "alice", "S1");
        execute(plan, "alice", "S2");

        PlanView compensating = planService.cancelPlan(ik, plan.planId(), "alice", rid(),
                new PlanCancelRequest(key(), null));
        assertThat(compensating.status()).isEqualTo("COMPENSATING");
        assertThat(compensating.nextStepKey()).isNull();
        assertThat(compensating.compensationStepKey()).isEqualTo("S2");
        // S3 未执行，取消后仍是 PENDING 且不再允许执行
        assertApiStatus(() -> execute(plan, "alice", "S3"), HttpStatus.CONFLICT);

        // 逆序补偿：先补 S1 属于跳步，409
        assertApiStatus(() -> planService.compensateStep(ik, plan.planId(), "S1", "alice", rid(),
                new PlanCompensateRequest(key(), null, "证据", null)), HttpStatus.CONFLICT);

        // 补偿 S2 失败：仅保存诊断，S2 仍 APPLIED，计划仍 COMPENSATING（剩两项待补偿）
        PlanView failed = planService.compensateStep(ik, plan.planId(), "S2", "alice", rid(),
                new PlanCompensateRequest(key(), null, null, "下游系统超时"));
        assertThat(failed.status()).isEqualTo("COMPENSATING");
        assertThat(failed.compensationStepKey()).isEqualTo("S2");
        PlanStepView s2 = failed.steps().get(1);
        assertThat(s2.status()).isEqualTo("APPLIED");
        assertThat(s2.attempts()).hasSize(1);
        assertThat(s2.attempts().get(0).status()).isEqualTo("FAILED");
        assertThat(s2.attempts().get(0).failureDiagnostic()).isEqualTo("下游系统超时");
        assertThat(s2.attempts().get(0).evidence()).isNull();
        assertThat(s2.attempts().get(0).requestId()).startsWith("REQ-");
        // 原效果说明仍保留
        assertThat(s2.effectDesc()).isEqualTo("合成效果-S2");

        // COMPENSATING 阻止事件进入 RESOLVED（先推进到 CONTAINED）
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> incidentService.changeStatus(ik, "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);

        // 用新 commandKey 重试补偿 S2 成功
        PlanView s2Done = planService.compensateStep(ik, plan.planId(), "S2", "alice", rid(),
                new PlanCompensateRequest(key(), null, "S2 已回滚", null));
        assertThat(s2Done.status()).isEqualTo("COMPENSATING");
        assertThat(s2Done.compensationStepKey()).isEqualTo("S1");
        assertThat(s2Done.steps().get(1).status()).isEqualTo("COMPENSATED");
        assertThat(s2Done.steps().get(1).attempts()).hasSize(2);
        assertThat(s2Done.steps().get(1).attempts().get(1).status()).isEqualTo("SUCCEEDED");
        assertThat(s2Done.steps().get(1).attempts().get(1).evidence()).isEqualTo("S2 已回滚");

        // 再补偿 S1，全部完成后 CANCELLED
        PlanView cancelled = planService.compensateStep(ik, plan.planId(), "S1", "alice", rid(),
                new PlanCompensateRequest(key(), null, "S1 已回滚", null));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.compensationStepKey()).isNull();
        assertThat(cancelled.steps()).extracting(PlanStepView::status)
                .containsExactly("COMPENSATED", "COMPENSATED", "PENDING");

        // 全部计划终结后可解决（任务门禁也满足：无任务）
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
        assertThat(incidentService.get(ik).status()).isEqualTo("RESOLVED");
    }

    @Test
    void cancelCompletedPlan_compensatesAllAppliedThenCancelled() {
        String ik = commanding("INC-PLAN-4", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        execute(plan, "alice", "S1");
        execute(plan, "alice", "S2");
        execute(plan, "alice", "S3");

        PlanView compensating = planService.cancelPlan(ik, plan.planId(), "alice", rid(),
                new PlanCancelRequest(key(), null));
        assertThat(compensating.status()).isEqualTo("COMPENSATING");
        assertThat(compensating.compensationStepKey()).isEqualTo("S3");

        for (String stepKey : List.of("S3", "S2", "S1")) {
            PlanView v = planService.compensateStep(ik, plan.planId(), stepKey, "alice", rid(),
                    new PlanCompensateRequest(key(), null, "回滚-" + stepKey, null));
            assertThat(v.compensationStepKey())
                    .isEqualTo(stepKey.equals("S1") ? null
                            : (stepKey.equals("S3") ? "S2" : "S1"));
        }
        assertThat(planService.getPlan(ik, plan.planId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelBeforeAnyExecution_goesStraightToCancelled() {
        String ik = commanding("INC-PLAN-5", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        PlanView cancelled = planService.cancelPlan(ik, plan.planId(), "alice", rid(),
                new PlanCancelRequest(key(), null));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.compensationStepKey()).isNull();
        assertThat(cancelled.steps()).extracting(PlanStepView::status)
                .containsExactly("PENDING", "PENDING", "PENDING");
    }

    @Test
    void nonCommanderForbidden_evenOnIdempotentReplay() {
        String ik = commanding("INC-PLAN-6", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        // 非指挥人执行：403（权限先于一切）
        assertApiStatus(() -> execute(plan, "mallory", "S1"), HttpStatus.FORBIDDEN);

        String commandKey = key();
        planService.executeStep(ik, plan.planId(), "S1", "alice", "REQ-A",
                new PlanStepExecuteRequest(commandKey, null));
        // 旧指挥/他人携带已成功键重放：仍 403，不返回首次响应
        assertApiStatus(() -> planService.executeStep(ik, plan.planId(), "S1", "mallory", "REQ-A",
                new PlanStepExecuteRequest(commandKey, null)), HttpStatus.FORBIDDEN);
    }

    @Test
    void afterTransfer_newCommanderCompensatesAndOldCommanderForbidden() {
        String ik = commanding("INC-PLAN-7", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        execute(plan, "alice", "S1");
        execute(plan, "alice", "S2");
        planService.cancelPlan(ik, plan.planId(), "alice", rid(),
                new PlanCancelRequest(key(), null));

        incidentService.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
        incidentService.acceptTransfer(ik, "bob", new TransferAcceptRequest(key()));

        // 旧指挥继续补偿：403
        assertApiStatus(() -> planService.compensateStep(ik, plan.planId(), "S2", "alice", rid(),
                new PlanCompensateRequest(key(), null, "证据", null)), HttpStatus.FORBIDDEN);
        // 新指挥可继续逆序补偿
        PlanView v = planService.compensateStep(ik, plan.planId(), "S2", "bob", rid(),
                new PlanCompensateRequest(key(), null, "bob 回滚 S2", null));
        assertThat(v.steps().get(1).status()).isEqualTo("COMPENSATED");
        assertThat(v.steps().get(1).compensatedBy()).isEqualTo("bob");
    }

    @Test
    void sameCommandKey_replaysFirstResponse_andDifferentParamsConflict() {
        String ik = commanding("INC-PLAN-8", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        String commandKey = key();
        PlanView first = planService.executeStep(ik, plan.planId(), "S1", "alice", "REQ-X",
                new PlanStepExecuteRequest(commandKey, null));
        // 同键同参重放：返回首次完整响应，不新增效果
        PlanView replay = planService.executeStep(ik, plan.planId(), "S1", "alice", "REQ-X",
                new PlanStepExecuteRequest(commandKey, null));
        assertThat(replay).isEqualTo(first);
        Integer appliedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_plan_steps WHERE plan_id = ? AND status = 'APPLIED'",
                Integer.class, plan.planId());
        assertThat(appliedCount).isEqualTo(1);
        // 同键换 requestId（异参）：409
        assertApiStatus(() -> planService.executeStep(ik, plan.planId(), "S1", "alice", "REQ-Y",
                new PlanStepExecuteRequest(commandKey, null)), HttpStatus.CONFLICT);
    }

    @Test
    void failedBusinessCall_doesNotOccupyCommandKey() {
        String ik = commanding("INC-PLAN-9", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        String commandKey = key();
        // 跳步执行 S2 业务失败：409，幂等键不被占用
        assertApiStatus(() -> planService.executeStep(ik, plan.planId(), "S2", "alice", rid(),
                new PlanStepExecuteRequest(commandKey, null)), HttpStatus.CONFLICT);
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(keyRows).isZero();
        // 同一键可用于合法的 S1 执行
        PlanView ok = planService.executeStep(ik, plan.planId(), "S1", "alice", rid(),
                new PlanStepExecuteRequest(commandKey, null));
        assertThat(ok.steps().get(0).status()).isEqualTo("APPLIED");
    }

    @Test
    void expectedVersion_conflictOnStaleVersion() {
        String ik = commanding("INC-PLAN-10", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        // 初始版本 0，携带错误期望版本 5：409
        assertApiStatus(() -> planService.executeStep(ik, plan.planId(), "S1", "alice", rid(),
                new PlanStepExecuteRequest(key(), 5L)), HttpStatus.CONFLICT);
        // 正确版本 0 放行
        PlanView after = planService.executeStep(ik, plan.planId(), "S1", "alice", rid(),
                new PlanStepExecuteRequest(key(), 0L));
        assertThat(after.version()).isEqualTo(1);
    }

    @Test
    void compensatePayload_requiresExactlyOneOfEvidenceOrDiagnostic() {
        String ik = commanding("INC-PLAN-11", "alice");
        PlanView plan = createThreeStepPlan(ik, "alice");
        execute(plan, "alice", "S1");
        planService.cancelPlan(ik, plan.planId(), "alice", rid(),
                new PlanCancelRequest(key(), null));
        // 两者都空：400
        assertApiStatus(() -> planService.compensateStep(ik, plan.planId(), "S1", "alice", rid(),
                new PlanCompensateRequest(key(), null, null, null)), HttpStatus.BAD_REQUEST);
        // 两者都给：400
        assertApiStatus(() -> planService.compensateStep(ik, plan.planId(), "S1", "alice", rid(),
                new PlanCompensateRequest(key(), null, "证据", "诊断")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void createPlan_validationAndStateGuards() {
        String ik = commanding("INC-PLAN-12", "alice");
        // 步骤数为 0：400
        assertApiStatus(() -> planService.createPlan(ik, "alice", rid(),
                new PlanCreateRequest(key(), List.of())), HttpStatus.BAD_REQUEST);
        // 超过 5 步：400
        List<PlanStepDefinition> six = List.of(step("A"), step("B"), step("C"),
                step("D"), step("E"), step("F"));
        assertApiStatus(() -> planService.createPlan(ik, "alice", rid(),
                new PlanCreateRequest(key(), six)), HttpStatus.BAD_REQUEST);
        // stepKey 重复：400
        assertApiStatus(() -> planService.createPlan(ik, "alice", rid(),
                new PlanCreateRequest(key(), List.of(step("A"), step("A")))),
                HttpStatus.BAD_REQUEST);
        // 缺请求头/操作者为空：400
        assertApiStatus(() -> planService.createPlan(ik, "  ", rid(),
                new PlanCreateRequest(key(), List.of(step("A")))), HttpStatus.BAD_REQUEST);
    }
}
