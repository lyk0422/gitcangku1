package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.VoteWithdrawRequest;
import com.example.starter.incident.dto.Responses.ProposalDiagnosticsView;
import com.example.starter.incident.dto.Responses.ProposalView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.VoteView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 处置提案服务测试：覆盖登记校验、投票计票、法定人数生效原子写任务、左闭右开到期 410、
 * 同角色一票、撤回、任务修订旧票 STALE、阻塞门禁失败回滚、只读诊断与 commandKey 幂等。
 * 时间断言使用可控 Clock，不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class DecisionServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM decision_votes");
        jdbc.update("DELETE FROM decision_required_roles");
        jdbc.update("DELETE FROM incident_decisions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String taskKey) {
        incidentService.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "DB", "扩容", List.of()));
    }

    private ProposalView proposal(String incidentKey, String proposalKey, String type,
                                  Instant expiresAt, List<String> roles, int quorum) {
        return decisionService.createProposal(incidentKey, "alice",
                new ProposalCreateRequest(key(), proposalKey, "T-1", type,
                        expiresAt, roles, quorum));
    }

    private ProposalView vote(String incidentKey, String proposalKey, String actor,
                              String role, String choice) {
        return decisionService.vote(incidentKey, proposalKey, actor,
                new VoteRequest(key(), role, choice));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void createProposal_mainFlow_snapshotsRolesAndRevision() {
        commanding("INC-D1", "alice");
        createTask("INC-D1", "T-1");
        Instant expiresAt = T0.plusSeconds(600);

        ProposalView view = decisionService.createProposal("INC-D1", "alice",
                new ProposalCreateRequest(key(), "P-1", "T-1", "TASK_COMPLETE",
                        expiresAt, List.of("OPS", "DB", "SRE"), 2));

        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.proposalKey()).isEqualTo("P-1");
        assertThat(view.taskKey()).isEqualTo("T-1");
        assertThat(view.decisionType()).isEqualTo("TASK_COMPLETE");
        assertThat(view.createdBy()).isEqualTo("alice");
        assertThat(view.createdAt()).isEqualTo(T0);
        assertThat(view.expiresAt()).isEqualTo(expiresAt);
        // 角色集合规范化（去重排序）后稳定返回
        assertThat(view.requiredRoles()).containsExactly("DB", "OPS", "SRE");
        assertThat(view.quorum()).isEqualTo(2);
        assertThat(view.baseTaskRevision()).isEqualTo(1);
        assertThat(view.taskRevision()).isEqualTo(1);
        assertThat(view.supportVotes()).isZero();
        assertThat(view.opposeVotes()).isZero();
        // 差额：要求值 2 - 实际值 0
        assertThat(view.remainingVotes()).isEqualTo(2);
        assertThat(view.effectiveAt()).isNull();
        assertThat(view.effectiveBy()).isNull();
        assertThat(view.effectiveTaskRevision()).isNull();
        assertThat(view.expiredAt()).isNull();
        assertThat(view.votes()).isEmpty();

        // 明细与列表读取
        assertThat(decisionService.getProposal("INC-D1", "P-1")).isEqualTo(view);
        assertThat(decisionService.listProposals("INC-D1").proposals()).containsExactly(view);
    }

    @Test
    void createProposal_validation_422And409() {
        commanding("INC-D2", "alice");
        createTask("INC-D2", "T-1");

        // 角色集合为空 → 422
        assertApiStatus(() -> decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E1", "T-1", "TASK_COMPLETE",
                        T0.plusSeconds(600), List.of(), 1)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 法定人数超过角色数 → 422
        ApiException tooMany = catchApi(() -> decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E2", "T-1", "TASK_COMPLETE",
                        T0.plusSeconds(600), List.of("OPS", "DB"), 3)));
        assertThat(tooMany.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMany.code()).isEqualTo("INVALID_PROPOSAL");
        // 法定人数 < 1 → 422
        assertApiStatus(() -> decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E3", "T-1", "TASK_COMPLETE",
                        T0.plusSeconds(600), List.of("OPS"), 0)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 到期时刻早于当前时钟 → 422
        assertApiStatus(() -> decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E4", "T-1", "TASK_COMPLETE",
                        T0.minusSeconds(1), List.of("OPS"), 1)), HttpStatus.UNPROCESSABLE_ENTITY);

        // 非当前指挥人 → 409
        assertApiStatus(() -> decisionService.createProposal("INC-D2", "bob",
                new ProposalCreateRequest(key(), "P-E5", "T-1", "TASK_COMPLETE",
                        T0.plusSeconds(600), List.of("OPS"), 1)), HttpStatus.CONFLICT);
        // 任务不存在 → 404
        assertApiStatus(() -> decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E6", "T-9", "TASK_COMPLETE",
                        T0.plusSeconds(600), List.of("OPS"), 1)), HttpStatus.NOT_FOUND);
        // proposalKey 重复 → 409
        proposal("INC-D2", "P-DUP", "TASK_COMPLETE", T0.plusSeconds(600), List.of("OPS"), 1);
        assertApiStatus(() -> proposal("INC-D2", "P-DUP", "TASK_COMPLETE",
                T0.plusSeconds(600), List.of("OPS"), 1), HttpStatus.CONFLICT);
        // decisionType 非法 → 400
        assertApiStatus(() -> decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E7", "T-1", "TASK_DELETE",
                        T0.plusSeconds(600), List.of("OPS"), 1)), HttpStatus.BAD_REQUEST);
        // 终态任务不能登记提案
        decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E8", "T-1", "TASK_CANCEL",
                        T0.plusSeconds(600), List.of("OPS", "DB"), 1));
        vote("INC-D2", "P-E8", "u1", "OPS", "SUPPORT");
        assertThat(incidentService.getTask("INC-D2", "T-1").status()).isEqualTo("CANCELLED");
        assertApiStatus(() -> decisionService.createProposal("INC-D2", "alice",
                new ProposalCreateRequest(key(), "P-E9", "T-1", "TASK_COMPLETE",
                        T0.plusSeconds(600), List.of("OPS"), 1)), HttpStatus.CONFLICT);
    }

    @Test
    void vote_reachQuorum_effectiveAtomicTaskComplete() {
        commanding("INC-D3", "alice");
        createTask("INC-D3", "T-1");
        proposal("INC-D3", "P-1", "TASK_COMPLETE", T0.plusSeconds(600),
                List.of("OPS", "DB", "SRE"), 2);

        ProposalView afterFirst = vote("INC-D3", "P-1", "u1", "OPS", "SUPPORT");
        assertThat(afterFirst.status()).isEqualTo("OPEN");
        assertThat(afterFirst.supportVotes()).isEqualTo(1);
        assertThat(afterFirst.remainingVotes()).isEqualTo(1);
        TaskView stillOpen = incidentService.getTask("INC-D3", "T-1");
        assertThat(stillOpen.status()).isEqualTo("OPEN");

        // 反对票不计入法定人数
        ProposalView afterOppose = vote("INC-D3", "P-1", "u2", "SRE", "OPPOSE");
        assertThat(afterOppose.supportVotes()).isEqualTo(1);
        assertThat(afterOppose.opposeVotes()).isEqualTo(1);
        assertThat(afterOppose.status()).isEqualTo("OPEN");

        // 第二张支持票达法定人数：提案生效，任务原子完成，固化版本/角色/时刻
        ProposalView effective = vote("INC-D3", "P-1", "u3", "DB", "SUPPORT");
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.supportVotes()).isEqualTo(2);
        assertThat(effective.opposeVotes()).isEqualTo(1);
        assertThat(effective.remainingVotes()).isZero();
        assertThat(effective.effectiveAt()).isEqualTo(T0);
        assertThat(effective.effectiveBy()).isEqualTo("u3");
        assertThat(effective.effectiveTaskRevision()).isEqualTo(1);
        assertThat(incidentService.getTask("INC-D3", "T-1").status()).isEqualTo("DONE");
        assertThat(incidentService.getTask("INC-D3", "T-1").doneBy()).isEqualTo("u3");

        // 三张票均作为历史证据保留，按落库顺序稳定排序
        assertThat(effective.votes()).hasSize(3);
        assertThat(effective.votes().stream().map(VoteView::roleCode))
                .containsExactly("OPS", "SRE", "DB");
        assertThat(effective.votes()).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo("CAST");
            assertThat(v.taskRevision()).isEqualTo(1);
            assertThat(v.staleAt()).isNull();
            assertThat(v.withdrawnAt()).isNull();
        });
    }

    @Test
    void vote_cancelType_marksTaskCancelled() {
        commanding("INC-D4", "alice");
        createTask("INC-D4", "T-1");
        proposal("INC-D4", "P-1", "TASK_CANCEL", T0.plusSeconds(600), List.of("OPS"), 1);
        ProposalView effective = vote("INC-D4", "P-1", "u1", "OPS", "SUPPORT");
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.effectiveBy()).isEqualTo("u1");
        assertThat(incidentService.getTask("INC-D4", "T-1").status()).isEqualTo("CANCELLED");
    }

    @Test
    void vote_invalidRoleAndDuplicateRole_rejected() {
        commanding("INC-D5", "alice");
        createTask("INC-D5", "T-1");
        proposal("INC-D5", "P-1", "TASK_COMPLETE", T0.plusSeconds(600),
                List.of("OPS", "DB"), 2);

        // 角色不在所需集合 → 400
        assertApiStatus(() -> vote("INC-D5", "P-1", "u1", "BOSS", "SUPPORT"),
                HttpStatus.BAD_REQUEST);
        // 立场非法 → 400
        assertApiStatus(() -> decisionService.vote("INC-D5", "P-1", "u1",
                new VoteRequest(key(), "OPS", "YES")), HttpStatus.BAD_REQUEST);

        vote("INC-D5", "P-1", "u1", "OPS", "SUPPORT");
        // 同角色同提案仅一票：即便操作者不同也拒绝
        assertApiStatus(() -> vote("INC-D5", "P-1", "u2", "OPS", "SUPPORT"),
                HttpStatus.CONFLICT);
        // 同一人换角色可以投
        ProposalView second = vote("INC-D5", "P-1", "u1", "DB", "SUPPORT");
        assertThat(second.supportVotes()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("EFFECTIVE");
        // 已生效再投 → 409
        assertApiStatus(() -> vote("INC-D5", "P-1", "u9", "DB", "OPPOSE"),
                HttpStatus.CONFLICT);
    }

    @Test
    void vote_atExpiry_returns410_andExpiresProposal_leftClosedRightOpen() {
        commanding("INC-D6", "alice");
        createTask("INC-D6", "T-1");
        Instant expiresAt = T0.plusSeconds(600);
        proposal("INC-D6", "P-1", "TASK_COMPLETE", expiresAt, List.of("OPS", "DB"), 2);

        // 到期前一秒：区间内可投
        ((ControllableClock) clock).setInstant(expiresAt.minusSeconds(1));
        assertThat(vote("INC-D6", "P-1", "u1", "OPS", "SUPPORT").status()).isEqualTo("OPEN");

        // 到达到期时刻当刻：左闭右开，投票 410 且提案固化 EXPIRED
        ((ControllableClock) clock).setInstant(expiresAt);
        assertApiStatus(() -> vote("INC-D6", "P-1", "u2", "DB", "SUPPORT"),
                HttpStatus.GONE);
        ProposalView expired = decisionService.getProposal("INC-D6", "P-1");
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.expiredAt()).isEqualTo(expiresAt);
        assertThat(expired.effectiveAt()).isNull();
        // 到期后任何投票都 410
        ((ControllableClock) clock).setInstant(expiresAt.plusSeconds(30));
        assertApiStatus(() -> vote("INC-D6", "P-1", "u3", "DB", "SUPPORT"),
                HttpStatus.GONE);
        // 到期后撤回也 410
        assertApiStatus(() -> decisionService.withdraw("INC-D6", "P-1", "u1",
                new VoteWithdrawRequest(key())), HttpStatus.GONE);
        // 任务未被改动
        assertThat(incidentService.getTask("INC-D6", "T-1").status()).isEqualTo("OPEN");
        // 已投的票保留为 CAST 历史
        assertThat(expired.votes()).hasSize(1);
        assertThat(expired.votes().get(0).status()).isEqualTo("CAST");
    }

    @Test
    void withdraw_freesRole_andPreservesHistory() {
        commanding("INC-D7", "alice");
        createTask("INC-D7", "T-1");
        // 法定人数 2：单张支持票不生效，提案保持 OPEN，可撤回
        proposal("INC-D7", "P-1", "TASK_COMPLETE", T0.plusSeconds(600),
                List.of("OPS", "DB"), 2);
        vote("INC-D7", "P-1", "u1", "OPS", "SUPPORT");

        // 别人不能撤回
        assertApiStatus(() -> decisionService.withdraw("INC-D7", "P-1", "u2",
                new VoteWithdrawRequest(key())), HttpStatus.CONFLICT);

        ProposalView withdrawn = decisionService.withdraw("INC-D7", "P-1", "u1",
                new VoteWithdrawRequest(key()));
        assertThat(withdrawn.supportVotes()).isZero();
        assertThat(withdrawn.remainingVotes()).isEqualTo(2);
        VoteView history = withdrawn.votes().get(0);
        assertThat(history.status()).isEqualTo("WITHDRAWN");
        assertThat(history.withdrawnAt()).isEqualTo(T0);
        assertThat(history.staleAt()).isNull();
        // 撤回后同角色可重新投，与另一角色合计达法定人数后生效
        vote("INC-D7", "P-1", "u1", "OPS", "SUPPORT");
        ProposalView effective = vote("INC-D7", "P-1", "u9", "DB", "SUPPORT");
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.votes()).hasSize(3);
        // 排序稳定：WITHDRAWN 历史在前，两张新 CAST 在后；历史证据字段未被覆盖
        assertThat(effective.votes().get(0).status()).isEqualTo("WITHDRAWN");
        assertThat(effective.votes().get(1).status()).isEqualTo("CAST");
        assertThat(effective.votes().get(2).status()).isEqualTo("CAST");
        assertThat(effective.votes().get(0).votedAt()).isEqualTo(T0);
        // 生效后撤回 → 409
        assertApiStatus(() -> decisionService.withdraw("INC-D7", "P-1", "u1",
                new VoteWithdrawRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void taskRevision_oldVotesBecomeStale_andHistoryPreserved() {
        commanding("INC-D8", "alice");
        createTask("INC-D8", "T-1");
        proposal("INC-D8", "P-1", "TASK_COMPLETE", T0.plusSeconds(600),
                List.of("OPS", "DB", "SRE"), 3);
        vote("INC-D8", "P-1", "u1", "OPS", "SUPPORT");
        vote("INC-D8", "P-1", "u2", "DB", "SUPPORT");

        // 修订任务：版本 1 → 2，旧票全部 STALE 且不计入新版本
        TaskView revised = decisionService.reviseTask("INC-D8", "T-1", "alice",
                new TaskReviseRequest(key(), "扩容-修订版", "修订说明"));
        assertThat(revised.title()).isEqualTo("扩容-修订版");
        ProposalView afterRevise = decisionService.getProposal("INC-D8", "P-1");
        assertThat(afterRevise.status()).isEqualTo("OPEN");
        assertThat(afterRevise.taskRevision()).isEqualTo(2);
        assertThat(afterRevise.baseTaskRevision()).isEqualTo(1);
        assertThat(afterRevise.supportVotes()).isZero();
        assertThat(afterRevise.remainingVotes()).isEqualTo(3);
        assertThat(afterRevise.votes()).hasSize(2);
        assertThat(afterRevise.votes()).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo("STALE");
            assertThat(v.taskRevision()).isEqualTo(1);
            assertThat(v.staleAt()).isEqualTo(T0);
        });

        // 旧角色可在新版本重新投票，三张新版本票达法定人数后按新版本生效
        vote("INC-D8", "P-1", "u1", "OPS", "SUPPORT");
        vote("INC-D8", "P-1", "u2", "DB", "SUPPORT");
        ProposalView effective = vote("INC-D8", "P-1", "u3", "SRE", "SUPPORT");
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.effectiveTaskRevision()).isEqualTo(2);
        assertThat(effective.supportVotes()).isEqualTo(3);
        // 历史：2 张 STALE + 3 张 CAST，按落库顺序，STALE 行不被覆盖
        assertThat(effective.votes()).hasSize(5);
        assertThat(effective.votes().stream().filter(v -> v.status().equals("STALE"))).hasSize(2);
        assertThat(effective.votes().stream().filter(v -> v.status().equals("CAST"))).hasSize(3);
        assertThat(incidentService.getTask("INC-D8", "T-1").status()).isEqualTo("DONE");
    }

    @Test
    void revise_dueProposalsExpireBeforeStale_commitOrder() {
        commanding("INC-D9", "alice");
        createTask("INC-D9", "T-1");
        Instant expiresAt = T0.plusSeconds(600);
        proposal("INC-D9", "P-1", "TASK_COMPLETE", expiresAt, List.of("OPS", "DB"), 2);
        vote("INC-D9", "P-1", "u1", "OPS", "SUPPORT");

        // 到期后修订：已到期的 OPEN 提案先固化 EXPIRED，票不转 STALE
        ((ControllableClock) clock).setInstant(expiresAt.plusSeconds(10));
        decisionService.reviseTask("INC-D9", "T-1", "alice",
                new TaskReviseRequest(key(), "新标题", "到期后修订"));
        ProposalView expired = decisionService.getProposal("INC-D9", "P-1");
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.expiredAt()).isEqualTo(expiresAt.plusSeconds(10));
        assertThat(expired.taskRevision()).isEqualTo(2);
        assertThat(expired.votes().get(0).status()).isEqualTo("CAST");
        assertThat(expired.votes().get(0).staleAt()).isNull();
    }

    @Test
    void expiredProposalHistory_notRewrittenByLaterTaskRevision() {
        commanding("INC-D16", "alice");
        createTask("INC-D16", "T-1");
        Instant expiresAt = T0.plusSeconds(600);
        proposal("INC-D16", "P-1", "TASK_COMPLETE", expiresAt, List.of("OPS", "DB", "SRE"), 3);
        vote("INC-D16", "P-1", "u1", "OPS", "SUPPORT");
        vote("INC-D16", "P-1", "u2", "DB", "OPPOSE");

        // 到期触发固化
        ((ControllableClock) clock).setInstant(expiresAt);
        assertApiStatus(() -> vote("INC-D16", "P-1", "u3", "SRE", "SUPPORT"),
                HttpStatus.GONE);
        ProposalView expired = decisionService.getProposal("INC-D16", "P-1");
        assertThat(expired.status()).isEqualTo("EXPIRED");

        // 到期后任务修订：终态提案与投票历史不被改写，计票仍为到期终局值
        decisionService.reviseTask("INC-D16", "T-1", "alice",
                new TaskReviseRequest(key(), "修订标题", "到期后修订"));
        ProposalView after = decisionService.getProposal("INC-D16", "P-1");
        assertThat(after.status()).isEqualTo("EXPIRED");
        assertThat(after.expiredAt()).isEqualTo(expiresAt);
        assertThat(after.effectiveAt()).isNull();
        assertThat(after.taskRevision()).isEqualTo(2);
        assertThat(after.supportVotes()).isEqualTo(1);
        assertThat(after.opposeVotes()).isEqualTo(1);
        assertThat(after.remainingVotes()).isEqualTo(2);
        assertThat(after.votes()).hasSize(2);
        assertThat(after.votes()).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo("CAST");
            assertThat(v.taskRevision()).isEqualTo(1);
            assertThat(v.staleAt()).isNull();
            assertThat(v.withdrawnAt()).isNull();
        });
        ProposalDiagnosticsView diag = decisionService.diagnostics("INC-D16", "P-1");
        assertThat(diag.supportVotes()).isEqualTo(1);
        assertThat(diag.opposeVotes()).isEqualTo(1);
    }

    @Test
    void revise_requiresCommanderAndOpenTask() {        commanding("INC-D10", "alice");
        createTask("INC-D10", "T-1");
        assertApiStatus(() -> decisionService.reviseTask("INC-D10", "T-1", "bob",
                new TaskReviseRequest(key(), "t", "n")), HttpStatus.CONFLICT);
        incidentService.cancelTask("INC-D10", "T-1", "alice",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(key()));
        assertApiStatus(() -> decisionService.reviseTask("INC-D10", "T-1", "alice",
                new TaskReviseRequest(key(), "t", "n")), HttpStatus.CONFLICT);
        assertApiStatus(() -> decisionService.reviseTask("INC-D10", "T-9", "alice",
                new TaskReviseRequest(key(), "t", "n")), HttpStatus.NOT_FOUND);
    }

    @Test
    void effectiveDecision_completeBlockerGateFailure_rollsBackAndFreesCommandKey() {
        commanding("INC-D11", "alice");
        commanding("INC-B11", "bob");
        incidentService.createTask("INC-D11", "alice",
                new TaskCreateRequest(key(), "T-1", "DB", "扩容", List.of("INC-B11")));
        proposal("INC-D11", "P-1", "TASK_COMPLETE", T0.plusSeconds(600),
                List.of("OPS", "DB"), 2);
        vote("INC-D11", "P-1", "u1", "OPS", "SUPPORT");

        // 达法定人数但阻塞事件未解除：409 且整事务回滚——票不半留、任务不变、提案仍 OPEN
        String commandKey = key();
        ApiException blocked = catchApi(() -> decisionService.vote("INC-D11", "P-1", "u2",
                new VoteRequest(commandKey, "DB", "SUPPORT")));
        assertThat(blocked.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.details()).isEqualTo(List.of("INC-B11"));
        ProposalView open = decisionService.getProposal("INC-D11", "P-1");
        assertThat(open.status()).isEqualTo("OPEN");
        assertThat(open.supportVotes()).isEqualTo(1);
        assertThat(open.votes()).hasSize(1);
        assertThat(incidentService.getTask("INC-D11", "T-1").status()).isEqualTo("OPEN");
        // 失败不占键：同一个 commandKey 可在阻塞解除后成功使用
        incidentService.changeStatus("INC-B11", "bob", new StatusRequest(key(), "CONTAINED"));
        ProposalView effective = decisionService.vote("INC-D11", "P-1", "u2",
                new VoteRequest(commandKey, "DB", "SUPPORT"));
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(incidentService.getTask("INC-D11", "T-1").status()).isEqualTo("DONE");
    }

    @Test
    void commandKey_sameParamsReplay_diffParams409_failureFree() {
        commanding("INC-D12", "alice");
        createTask("INC-D12", "T-1");
        String createKey = key();
        ProposalCreateRequest req = new ProposalCreateRequest(createKey, "P-1", "T-1",
                "TASK_CANCEL", T0.plusSeconds(600), List.of("OPS", "DB"), 1);
        ProposalView first = decisionService.createProposal("INC-D12", "alice", req);
        // 同键同参：重放首次成功响应快照
        assertThat(decisionService.createProposal("INC-D12", "alice", req)).isEqualTo(first);
        // 同键改 quorum：409
        assertApiStatus(() -> decisionService.createProposal("INC-D12", "alice",
                new ProposalCreateRequest(createKey, "P-1", "T-1", "TASK_CANCEL",
                        T0.plusSeconds(600), List.of("OPS", "DB"), 2)), HttpStatus.CONFLICT);
        // 同键改操作者：409
        assertApiStatus(() -> decisionService.createProposal("INC-D12", "bob",
                new ProposalCreateRequest(createKey, "P-1", "T-1", "TASK_CANCEL",
                        T0.plusSeconds(600), List.of("OPS", "DB"), 1)), HttpStatus.CONFLICT);

        // 投票同键重放
        String voteKey = key();
        ProposalView v1 = decisionService.vote("INC-D12", "P-1", "u1",
                new VoteRequest(voteKey, "OPS", "SUPPORT"));
        assertThat(decisionService.vote("INC-D12", "P-1", "u1",
                new VoteRequest(voteKey, "OPS", "SUPPORT"))).isEqualTo(v1);
        // 同键换角色：409
        assertApiStatus(() -> decisionService.vote("INC-D12", "P-1", "u1",
                new VoteRequest(voteKey, "DB", "SUPPORT")), HttpStatus.CONFLICT);
    }

    @Test
    void fingerprint_normalizedSets_notRawDelimiterJoin() {
        commanding("INC-D13", "alice");
        createTask("INC-D13", "T-1");
        String createKey = key();
        // 集合去重+排序规范化：两种写法指纹相同，同键重放首次快照
        ProposalCreateRequest unordered = new ProposalCreateRequest(createKey, "P-1", "T-1",
                "TASK_CANCEL", T0.plusSeconds(600), List.of("DB", "OPS"), 1);
        ProposalView first = decisionService.createProposal("INC-D13", "alice", unordered);
        ProposalCreateRequest normalized = new ProposalCreateRequest(createKey, "P-1", "T-1",
                "TASK_CANCEL", T0.plusSeconds(600), List.of("OPS", "DB", "DB"), 1);
        assertThat(decisionService.createProposal("INC-D13", "alice", normalized)).isEqualTo(first);
        // 单角色 "OPS,DB" 与两角色 ["OPS","DB"] 不得碰撞为同一指纹：原始分隔符拼接会碰撞，JSON 编码不会
        assertApiStatus(() -> decisionService.createProposal("INC-D13", "alice",
                new ProposalCreateRequest(createKey, "P-1", "T-1", "TASK_CANCEL",
                        T0.plusSeconds(600), List.of("OPS,DB"), 1)), HttpStatus.CONFLICT);
    }

    @Test
    void diagnostics_returnsActualRequiredAndShortfall_andReadOnly() {
        commanding("INC-D14", "alice");
        createTask("INC-D14", "T-1");
        Instant expiresAt = T0.plusSeconds(600);
        proposal("INC-D14", "P-1", "TASK_COMPLETE", expiresAt, List.of("OPS", "DB", "SRE"), 2);
        vote("INC-D14", "P-1", "u1", "OPS", "SUPPORT");

        ((ControllableClock) clock).setInstant(T0.plusSeconds(300));
        ProposalDiagnosticsView diag = decisionService.diagnostics("INC-D14", "P-1");
        assertThat(diag.now()).isEqualTo(T0.plusSeconds(300));
        assertThat(diag.votingOpen()).isTrue();
        assertThat(diag.secondsRemaining()).isEqualTo(300);
        assertThat(diag.quorum()).isEqualTo(2);
        assertThat(diag.supportVotes()).isEqualTo(1);
        assertThat(diag.opposeVotes()).isZero();
        assertThat(diag.remainingVotes()).isEqualTo(1);
        assertThat(diag.activeCastVotes()).isEqualTo(1);
        assertThat(diag.activeRoleCount()).isEqualTo(1);
        assertThat(diag.requiredRoleCount()).isEqualTo(3);
        assertThat(diag.missingRoles()).containsExactly("DB", "SRE");
        assertThat(diag.taskRevision()).isEqualTo(1);
        assertThat(diag.baseTaskRevision()).isEqualTo(1);

        // 只读：时钟越过到期时刻后反复读取诊断，提案不得被隐式置 EXPIRED
        ((ControllableClock) clock).setInstant(expiresAt.plusSeconds(1));
        ProposalDiagnosticsView staleDiag = decisionService.diagnostics("INC-D14", "P-1");
        assertThat(staleDiag.votingOpen()).isFalse();
        assertThat(staleDiag.secondsRemaining()).isZero();
        assertThat(decisionService.getProposal("INC-D14", "P-1").status()).isEqualTo("OPEN");
        Integer expired = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decisions WHERE status = 'EXPIRED'", Integer.class);
        assertThat(expired).isZero();
    }

    @Test
    void reads_notFound() {
        assertApiStatus(() -> decisionService.listProposals("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> decisionService.getProposal("INC-404", "P-1"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> decisionService.diagnostics("INC-404", "P-1"), HttpStatus.NOT_FOUND);
        commanding("INC-D15", "alice");
        assertApiStatus(() -> decisionService.getProposal("INC-D15", "P-404"), HttpStatus.NOT_FOUND);
        assertThat(decisionService.listProposals("INC-D15").proposals()).isEmpty();
    }

    private static ApiException catchApi(ThrowingCallable call) {
        try {
            call.call();
            throw new AssertionError("期望抛出 ApiException");
        } catch (ApiException e) {
            return e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }
}
