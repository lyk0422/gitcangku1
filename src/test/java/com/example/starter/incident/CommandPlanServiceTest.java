package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.PlanCancelRequest;
import com.example.starter.incident.dto.Requests.PlanCompensateRequest;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanExecuteRequest;
import com.example.starter.incident.dto.Requests.PlanStepInput;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.PlanView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指令计划服务测试：覆盖创建校验、顺序执行、跳步 409、重复执行幂等、
 * 取消补偿逆序、补偿失败诊断与重试、解决门禁、交接权限及 requestId 幂等语义。
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

    private IncidentView commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "合成故障", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static PlanCreateRequest threeStepPlan(String requestId, String planKey) {
        return new PlanCreateRequest(requestId, planKey, List.of(
                new PlanStepInput("S1", "效果1", "补偿1"),
                new PlanStepInput("S2", "效果2", "补偿2"),
                new PlanStepInput("S3", "效果3", "补偿3")));
    }

    @Test
    void createAndExecute_mainFlow() {
        commanding("INC-P1", "alice");
        PlanView created = planService.createPlan("INC-P1", "alice",
                threeStepPlan(key(), "PLAN-1"));
        assertThat(created.status()).isEqualTo("RUNNING");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.createdBy()).isEqualTo("alice");
        assertThat(created.steps()).hasSize(3);
        assertThat(created.steps().get(0).stepKey()).isEqualTo("S1");
        assertThat(created.steps().get(0).seq()).isEqualTo(1);
        assertThat(created.steps().get(0).status()).isEqualTo("PENDING");

        // 顺序执行 S1、S2、S3，全部执行后 COMPLETED
        PlanView afterS1 = planService.executeStep("INC-P1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(key(), 1L));
        assertThat(afterS1.steps().get(0).status()).isEqualTo("APPLIED");
        assertThat(afterS1.steps().get(0).appliedBy()).isEqualTo("alice");
        assertThat(afterS1.steps().get(0).appliedAt()).isNotNull();
        assertThat(afterS1.status()).isEqualTo("RUNNING");
        assertThat(afterS1.version()).isEqualTo(2);

        PlanView afterS2 = planService.executeStep("INC-P1", "PLAN-1", "S2", "alice",
                new PlanExecuteRequest(key(), 2L));
        assertThat(afterS2.steps().get(1).status()).isEqualTo("APPLIED");
        assertThat(afterS2.status()).isEqualTo("RUNNING");

        PlanView afterS3 = planService.executeStep("INC-P1", "PLAN-1", "S3", "alice",
                new PlanExecuteRequest(key(), 3L));
        assertThat(afterS3.steps().get(2).status()).isEqualTo("APPLIED");
        assertThat(afterS3.status()).isEqualTo("COMPLETED");
        assertThat(afterS3.version()).isEqualTo(4);
    }

    @Test
    void createPlan_validation() {
        commanding("INC-V1", "alice");
        // 空步骤
        assertApiStatus(() -> planService.createPlan("INC-V1", "alice",
                new PlanCreateRequest(key(), "P", List.of())), HttpStatus.BAD_REQUEST);
        // 超过 5 步
        assertApiStatus(() -> planService.createPlan("INC-V1", "alice",
                new PlanCreateRequest(key(), "P", List.of(
                        new PlanStepInput("S1", "e", "c"), new PlanStepInput("S2", "e", "c"),
                        new PlanStepInput("S3", "e", "c"), new PlanStepInput("S4", "e", "c"),
                        new PlanStepInput("S5", "e", "c"), new PlanStepInput("S6", "e", "c")))),
                HttpStatus.BAD_REQUEST);
        // stepKey 重复
        assertApiStatus(() -> planService.createPlan("INC-V1", "alice",
                new PlanCreateRequest(key(), "P", List.of(
                        new PlanStepInput("S1", "e", "c"), new PlanStepInput("S1", "e2", "c2")))),
                HttpStatus.BAD_REQUEST);
        // 效果说明为空
        assertApiStatus(() -> planService.createPlan("INC-V1", "alice",
                new PlanCreateRequest(key(), "P", List.of(new PlanStepInput("S1", " ", "c")))),
                HttpStatus.BAD_REQUEST);
        // 补偿说明为空
        assertApiStatus(() -> planService.createPlan("INC-V1", "alice",
                new PlanCreateRequest(key(), "P", List.of(new PlanStepInput("S1", "e", " ")))),
                HttpStatus.BAD_REQUEST);
        // requestId 为空
        assertApiStatus(() -> planService.createPlan("INC-V1", "alice",
                new PlanCreateRequest(" ", "P", List.of(new PlanStepInput("S1", "e", "c")))),
                HttpStatus.BAD_REQUEST);
        // 事件不存在
        assertApiStatus(() -> planService.createPlan("INC-404", "alice",
                new PlanCreateRequest(key(), "P", List.of(new PlanStepInput("S1", "e", "c")))),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void executeStep_jumpStepAndVersionConflict() {
        commanding("INC-J1", "alice");
        planService.createPlan("INC-J1", "alice", threeStepPlan(key(), "PLAN-1"));
        // 跳步执行 S2（当前下一步是 S1）→ 409
        assertApiStatus(() -> planService.executeStep("INC-J1", "PLAN-1", "S2", "alice",
                new PlanExecuteRequest(key(), 1L)), HttpStatus.CONFLICT);
        // 版本不一致 → 409
        assertApiStatus(() -> planService.executeStep("INC-J1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(key(), 99L)), HttpStatus.CONFLICT);
        // 计划/步骤不存在 → 404
        assertApiStatus(() -> planService.executeStep("INC-J1", "PLAN-9", "S1", "alice",
                new PlanExecuteRequest(key(), 1L)), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> planService.executeStep("INC-J1", "PLAN-1", "S9", "alice",
                new PlanExecuteRequest(key(), 1L)), HttpStatus.NOT_FOUND);
    }

    @Test
    void executeStep_reexecuteAppliedStep_idempotent() {
        commanding("INC-R1", "alice");
        planService.createPlan("INC-R1", "alice", threeStepPlan(key(), "PLAN-1"));
        PlanView first = planService.executeStep("INC-R1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(key(), 1L));
        // 再次执行旧成功步骤（不同 requestId）：不重复记效果，幂等返回
        PlanView again = planService.executeStep("INC-R1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(key(), 1L));
        assertThat(again.steps().get(0).status()).isEqualTo("APPLIED");
        assertThat(again.steps().get(0).appliedAt())
                .isEqualTo(first.steps().get(0).appliedAt());
        // 版本不因重复执行而递增
        assertThat(again.version()).isEqualTo(first.version());
    }

    @Test
    void cancelAndCompensate_reverseOrder() {
        commanding("INC-C1", "alice");
        planService.createPlan("INC-C1", "alice", threeStepPlan(key(), "PLAN-1"));
        planService.executeStep("INC-C1", "PLAN-1", "S1", "alice", new PlanExecuteRequest(key(), 1L));
        planService.executeStep("INC-C1", "PLAN-1", "S2", "alice", new PlanExecuteRequest(key(), 2L));
        // 取消 RUNNING 计划 → COMPENSATING
        PlanView cancelled = planService.cancelPlan("INC-C1", "PLAN-1", "alice",
                new PlanCancelRequest(key()));
        assertThat(cancelled.status()).isEqualTo("COMPENSATING");
        assertThat(cancelled.cancelledBy()).isEqualTo("alice");
        assertThat(cancelled.cancelledAt()).isNotNull();
        // 未执行步骤 S3 不再执行
        assertApiStatus(() -> planService.executeStep("INC-C1", "PLAN-1", "S3", "alice",
                new PlanExecuteRequest(key(), 4L)), HttpStatus.CONFLICT);
        // 跳步补偿 S1（应先补偿 S2）→ 409
        assertApiStatus(() -> planService.compensateStep("INC-C1", "PLAN-1", "S1", "alice",
                new PlanCompensateRequest(key(), "证据", 4L)), HttpStatus.CONFLICT);
        // 逆序补偿 S2 再 S1，全部补偿后 CANCELLED
        PlanView afterS2 = planService.compensateStep("INC-C1", "PLAN-1", "S2", "alice",
                new PlanCompensateRequest(key(), "补偿S2证据", 4L));
        assertThat(afterS2.steps().get(1).status()).isEqualTo("COMPENSATED");
        assertThat(afterS2.steps().get(1).compensatedBy()).isEqualTo("alice");
        assertThat(afterS2.status()).isEqualTo("COMPENSATING");
        PlanView afterS1 = planService.compensateStep("INC-C1", "PLAN-1", "S1", "alice",
                new PlanCompensateRequest(key(), "补偿S1证据", 5L));
        assertThat(afterS1.steps().get(0).status()).isEqualTo("COMPENSATED");
        assertThat(afterS1.status()).isEqualTo("CANCELLED");
        // 补偿证据已保存
        assertThat(afterS1.compensations()).hasSize(2);
        assertThat(afterS1.compensations().get(0).stepKey()).isEqualTo("S2");
        assertThat(afterS1.compensations().get(0).outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void compensateFailure_savesDiagnosticsAndAllowsRetry() {
        commanding("INC-F1", "alice");
        planService.createPlan("INC-F1", "alice", threeStepPlan(key(), "PLAN-1"));
        planService.executeStep("INC-F1", "PLAN-1", "S1", "alice", new PlanExecuteRequest(key(), 1L));
        planService.executeStep("INC-F1", "PLAN-1", "S2", "alice", new PlanExecuteRequest(key(), 2L));
        planService.cancelPlan("INC-F1", "PLAN-1", "alice", new PlanCancelRequest(key()));
        // 补偿 S2 失败（版本不一致）：仅保存失败诊断，S2 仍 APPLIED，仍剩两项
        PlanView failed = planService.compensateStep("INC-F1", "PLAN-1", "S2", "alice",
                new PlanCompensateRequest(key(), "失败证据", 99L));
        assertThat(failed.steps().get(1).status()).isEqualTo("APPLIED");
        assertThat(failed.steps().get(0).status()).isEqualTo("APPLIED");
        assertThat(failed.status()).isEqualTo("COMPENSATING");
        assertThat(failed.compensations()).hasSize(1);
        assertThat(failed.compensations().get(0).outcome()).isEqualTo("FAILED");
        assertThat(failed.compensations().get(0).diagnostics()).contains("版本不一致");
        // 不能执行 S3
        assertApiStatus(() -> planService.executeStep("INC-F1", "PLAN-1", "S3", "alice",
                new PlanExecuteRequest(key(), 4L)), HttpStatus.CONFLICT);
        // 不能解决事件
        incidentService.changeStatus("INC-F1", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> incidentService.changeStatus("INC-F1", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
        // 新 requestId 重试补偿 S2 成功
        PlanView retried = planService.compensateStep("INC-F1", "PLAN-1", "S2", "alice",
                new PlanCompensateRequest(key(), "重试证据", 4L));
        assertThat(retried.steps().get(1).status()).isEqualTo("COMPENSATED");
        assertThat(retried.compensations()).hasSize(2);
    }

    @Test
    void cancelPlan_noAppliedSteps_directlyCancelled() {
        commanding("INC-N1", "alice");
        planService.createPlan("INC-N1", "alice", threeStepPlan(key(), "PLAN-1"));
        PlanView cancelled = planService.cancelPlan("INC-N1", "PLAN-1", "alice",
                new PlanCancelRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        // 重复取消 → 409
        assertApiStatus(() -> planService.cancelPlan("INC-N1", "PLAN-1", "alice",
                new PlanCancelRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void resolveGate_plansBlockResolved() {
        commanding("INC-G1", "alice");
        planService.createPlan("INC-G1", "alice", threeStepPlan(key(), "PLAN-1"));
        incidentService.changeStatus("INC-G1", "alice", new StatusRequest(key(), "CONTAINED"));
        // RUNNING 计划阻止解决
        assertApiStatus(() -> incidentService.changeStatus("INC-G1", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
        // 全部执行后 COMPLETED 不阻止
        planService.executeStep("INC-G1", "PLAN-1", "S1", "alice", new PlanExecuteRequest(key(), 1L));
        planService.executeStep("INC-G1", "PLAN-1", "S2", "alice", new PlanExecuteRequest(key(), 2L));
        planService.executeStep("INC-G1", "PLAN-1", "S3", "alice", new PlanExecuteRequest(key(), 3L));
        IncidentView resolved = incidentService.changeStatus("INC-G1", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    @Test
    void handover_oldCommanderForbidden_newCommanderContinues() {
        commanding("INC-H1", "alice");
        planService.createPlan("INC-H1", "alice", threeStepPlan(key(), "PLAN-1"));
        planService.executeStep("INC-H1", "PLAN-1", "S1", "alice", new PlanExecuteRequest(key(), 1L));
        planService.cancelPlan("INC-H1", "PLAN-1", "alice", new PlanCancelRequest(key()));
        // 交接给 bob
        incidentService.initiateTransfer("INC-H1", "alice", new TransferRequest(key(), "bob"));
        incidentService.acceptTransfer("INC-H1", "bob", new TransferAcceptRequest(key()));
        // 旧指挥人继续补偿 → 403
        assertApiStatus(() -> planService.compensateStep("INC-H1", "PLAN-1", "S1", "alice",
                new PlanCompensateRequest(key(), "证据", 3L)), HttpStatus.FORBIDDEN);
        // 新指挥人继续补偿成功
        PlanView compensated = planService.compensateStep("INC-H1", "PLAN-1", "S1", "bob",
                new PlanCompensateRequest(key(), "证据", 3L));
        assertThat(compensated.steps().get(0).compensatedBy()).isEqualTo("bob");
        assertThat(compensated.status()).isEqualTo("CANCELLED");
    }

    @Test
    void requestId_idempotency() {
        commanding("INC-I1", "alice");
        // 创建：同 requestId 同参重放首次完整响应
        String createKey = key();
        PlanView first = planService.createPlan("INC-I1", "alice",
                threeStepPlan(createKey, "PLAN-1"));
        PlanView replay = planService.createPlan("INC-I1", "alice",
                threeStepPlan(createKey, "PLAN-1"));
        assertThat(replay).isEqualTo(first);
        assertThat(planService.listPlans("INC-I1").plans()).hasSize(1);
        // 同 requestId 改参 → 409
        assertApiStatus(() -> planService.createPlan("INC-I1", "alice",
                threeStepPlan(createKey, "PLAN-2")), HttpStatus.CONFLICT);
        // 执行：同 requestId 重放首次响应
        String execKey = key();
        PlanView execFirst = planService.executeStep("INC-I1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(execKey, 1L));
        PlanView execReplay = planService.executeStep("INC-I1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(execKey, 1L));
        assertThat(execReplay).isEqualTo(execFirst);
        // 同 requestId 改参（不同版本）→ 409
        assertApiStatus(() -> planService.executeStep("INC-I1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(execKey, 2L)), HttpStatus.CONFLICT);
    }

    @Test
    void requestId_failureDoesNotConsumeKey() {
        commanding("INC-K1", "alice");
        planService.createPlan("INC-K1", "alice", threeStepPlan(key(), "PLAN-1"));
        String failKey = key();
        // 业务失败（跳步）事务回滚，不占键
        assertApiStatus(() -> planService.executeStep("INC-K1", "PLAN-1", "S2", "alice",
                new PlanExecuteRequest(failKey, 1L)), HttpStatus.CONFLICT);
        // 同 requestId 可用于后续成功请求
        PlanView ok = planService.executeStep("INC-K1", "PLAN-1", "S1", "alice",
                new PlanExecuteRequest(failKey, 1L));
        assertThat(ok.steps().get(0).status()).isEqualTo("APPLIED");
    }

    @Test
    void permissionCheckedBeforeReplay() {
        commanding("INC-PB", "alice");
        String createKey = key();
        planService.createPlan("INC-PB", "alice", threeStepPlan(createKey, "PLAN-1"));
        // 交接后旧指挥人持旧 requestId 重放：权限先于重放，返回 403 而非首次响应
        incidentService.initiateTransfer("INC-PB", "alice", new TransferRequest(key(), "bob"));
        incidentService.acceptTransfer("INC-PB", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> planService.createPlan("INC-PB", "alice",
                threeStepPlan(createKey, "PLAN-1")), HttpStatus.FORBIDDEN);
    }

    @Test
    void planKey_idempotency() {
        commanding("INC-PK", "alice");
        PlanView first = planService.createPlan("INC-PK", "alice",
                threeStepPlan(key(), "PLAN-1"));
        // 不同 requestId、同 planKey 同内容：幂等返回首次计划
        PlanView again = planService.createPlan("INC-PK", "alice",
                threeStepPlan(key(), "PLAN-1"));
        assertThat(again).isEqualTo(first);
        assertThat(planService.listPlans("INC-PK").plans()).hasSize(1);
        // 同 planKey 不同内容 → 409
        assertApiStatus(() -> planService.createPlan("INC-PK", "alice",
                new PlanCreateRequest(key(), "PLAN-1", List.of(
                        new PlanStepInput("S1", "不同效果", "补偿1")))), HttpStatus.CONFLICT);
    }

    @Test
    void queries_readOnly() {
        commanding("INC-Q1", "alice");
        assertThat(planService.listPlans("INC-Q1").plans()).isEmpty();
        planService.createPlan("INC-Q1", "alice", threeStepPlan(key(), "PLAN-1"));
        assertThat(planService.listPlans("INC-Q1").plans()).hasSize(1);
        assertThat(planService.listPlans("INC-Q1").plans().get(0).planKey()).isEqualTo("PLAN-1");
        PlanView detail = planService.getPlan("INC-Q1", "PLAN-1");
        assertThat(detail.steps()).hasSize(3);
        // 不存在 → 404
        assertApiStatus(() -> planService.listPlans("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> planService.getPlan("INC-Q1", "PLAN-9"), HttpStatus.NOT_FOUND);
    }
}
