package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.DecisionCreateRequest;
import com.example.starter.incident.dto.Requests.DecisionExpireCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Requests.VoteWithdrawRequest;
import com.example.starter.incident.dto.Responses.DecisionDiagnosticsView;
import com.example.starter.incident.dto.Responses.DecisionHistoryView;
import com.example.starter.incident.dto.Responses.DecisionListView;
import com.example.starter.incident.dto.Responses.DecisionView;
import com.example.starter.incident.dto.Responses.IncidentView;
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
 * 决策提案服务测试：覆盖登记校验（422 含实际值/要求值/差额）、投票法定人数生效与
 * 任务变更原子写入、到期 410 与 EXPIRED 转移、撤回、任务修订致旧票 STALE、
 * 窗口左闭右开、commandKey 幂等语义与只读不改状态。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
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
        jdbc.update("DELETE FROM incident_decision_votes");
        jdbc.update("DELETE FROM incident_decisions");
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

    private IncidentView commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey) {
        return incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "DB", "扩容连接池", List.of()));
    }

    private DecisionView register(String incidentKey, String actor, String proposalKey,
                                  String taskKey, String action, List<String> roles, int quorum,
                                  Instant expiresAt) {
        return decisionService.register(incidentKey, actor,
                new DecisionCreateRequest(key(), proposalKey, taskKey, action, roles, quorum,
                        expiresAt));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void registerAndRead_mainFlow() {
        commanding("INC-D1", "alice");
        createTask("INC-D1", "alice", "T-1");
        Instant expiresAt = T0.plusSeconds(3600);
        DecisionView created = register("INC-D1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("ops", "dba", "net"), 2, expiresAt);

        assertThat(created.proposalKey()).isEqualTo("P-1");
        assertThat(created.taskKey()).isEqualTo("T-1");
        assertThat(created.taskAction()).isEqualTo("COMPLETE");
        assertThat(created.currentTaskVersion()).isEqualTo(1);
        assertThat(created.requiredRoles()).containsExactly("dba", "net", "ops");
        assertThat(created.quorum()).isEqualTo(2);
        assertThat(created.supportCount()).isZero();
        assertThat(created.opposeCount()).isZero();
        assertThat(created.remainingToQuorum()).isEqualTo(2);
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.decidedTaskVersion()).isNull();
        assertThat(created.decidedAt()).isNull();
        assertThat(created.expiresAt()).isEqualTo(expiresAt);
        assertThat(created.createdBy()).isEqualTo("alice");
        assertThat(created.createdAt()).isEqualTo(T0);

        // 明细、列表、历史、诊断均只读且内容一致
        assertThat(decisionService.get("INC-D1", "P-1")).isEqualTo(created);
        DecisionListView list = decisionService.list("INC-D1");
        assertThat(list.incidentKey()).isEqualTo("INC-D1");
        assertThat(list.decisions()).containsExactly(created);
        DecisionHistoryView history = decisionService.history("INC-D1", "P-1");
        assertThat(history.decision()).isEqualTo(created);
        assertThat(history.votes()).isEmpty();
        DecisionDiagnosticsView diagnostics = decisionService.diagnostics("INC-D1", "P-1");
        assertThat(diagnostics.status()).isEqualTo("OPEN");
        assertThat(diagnostics.quorum()).isEqualTo(2);
        assertThat(diagnostics.remainingToQuorum()).isEqualTo(2);
        assertThat(diagnostics.withdrawnCount()).isZero();
        assertThat(diagnostics.staleCount()).isZero();
        // 读取不改变状态：再次读取结果一致
        assertThat(decisionService.get("INC-D1", "P-1")).isEqualTo(created);
    }

    @Test
    void register_validation() {
        commanding("INC-V1", "alice");
        createTask("INC-V1", "alice", "T-1");
        Instant future = T0.plusSeconds(3600);
        // 角色集合为空 → 422，details 返回实际值与要求值
        assertThatThrownBy(() -> register("INC-V1", "alice", "P-1", "T-1", "COMPLETE",
                List.of(), 1, future))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details()).isEqualTo(Map.of("actualRoles", 0,
                            "requiredMinRoles", 1));
                });
        // 法定人数小于 1 → 422，含差额
        assertThatThrownBy(() -> register("INC-V1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("ops"), 0, future))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details()).isEqualTo(Map.of("actualQuorum", 0,
                            "requiredMinQuorum", 1, "deficit", 1));
                });
        // 法定人数超过角色集合 → 422，含超出差额
        assertThatThrownBy(() -> register("INC-V1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("ops", "dba"), 3, future))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details()).isEqualTo(Map.of("actualQuorum", 3,
                            "roleCount", 2, "excess", 1));
                });
        // 到期时刻早于当前时钟 → 422，含实际值与要求值
        assertThatThrownBy(() -> register("INC-V1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("ops"), 1, T0.minusSeconds(1)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details().toString()).contains("actualExpiresAt",
                            "requiredNotBefore");
                });
        // 缺参 → 400
        assertApiStatus(() -> decisionService.register("INC-V1", "alice",
                new DecisionCreateRequest(key(), "P-1", "T-1", "COMPLETE", List.of("ops"), null,
                        future)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> decisionService.register("INC-V1", "alice",
                new DecisionCreateRequest(key(), "P-1", "T-1", "COMPLETE", List.of("ops"), 1,
                        null)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> decisionService.register("INC-V1", "alice",
                new DecisionCreateRequest(key(), "P-1", "T-1", "COMPLETE", List.of(" "), 1,
                        future)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> decisionService.register("INC-V1", "alice",
                new DecisionCreateRequest(key(), "P-1", "T-1", "RESTART", List.of("ops"), 1,
                        future)), HttpStatus.BAD_REQUEST);
        // 任务不存在 → 404；事件不存在 → 404
        assertApiStatus(() -> register("INC-V1", "alice", "P-1", "T-9", "COMPLETE",
                List.of("ops"), 1, future), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> register("INC-404", "alice", "P-1", "T-1", "COMPLETE",
                List.of("ops"), 1, future), HttpStatus.NOT_FOUND);
        // 非当前指挥人 → 409
        assertApiStatus(() -> register("INC-V1", "bob", "P-1", "T-1", "COMPLETE",
                List.of("ops"), 1, future), HttpStatus.CONFLICT);
        // 终态任务 → 409
        incidentService.cancelTask("INC-V1", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> register("INC-V1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("ops"), 1, future), HttpStatus.CONFLICT);
    }

    @Test
    void register_proposalKeyIdempotencyAndSetNormalization() {
        commanding("INC-K1", "alice");
        createTask("INC-K1", "alice", "T-1");
        Instant future = T0.plusSeconds(3600);
        // 角色集合规范化：去重、排序；不同顺序的同集合指纹相同
        DecisionView first = decisionService.register("INC-K1", "alice",
                new DecisionCreateRequest(key(), "P-1", "T-1", "COMPLETE",
                        List.of("ops", "dba", "ops"), 2, future));
        assertThat(first.requiredRoles()).containsExactly("dba", "ops");
        // 同 proposalKey 同内容（不同 commandKey、角色顺序不同）：幂等返回首次提案
        DecisionView again = decisionService.register("INC-K1", "alice",
                new DecisionCreateRequest(key(), "P-1", "T-1", "COMPLETE",
                        List.of("dba", "ops"), 2, future));
        assertThat(again).isEqualTo(first);
        assertThat(decisionService.list("INC-K1").decisions()).hasSize(1);
        // 同 proposalKey 不同内容 → 409
        assertApiStatus(() -> register("INC-K1", "alice", "P-1", "T-1", "CANCEL",
                List.of("dba", "ops"), 2, future), HttpStatus.CONFLICT);
        assertApiStatus(() -> register("INC-K1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("dba", "ops"), 1, future), HttpStatus.CONFLICT);
        assertApiStatus(() -> register("INC-K1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("dba", "ops"), 2, future.plusSeconds(60)), HttpStatus.CONFLICT);
    }

    @Test
    void vote_quorumReached_effectiveAndTaskChangeAtomic() {
        commanding("INC-E1", "alice");
        createTask("INC-E1", "alice", "T-1");
        Instant expiresAt = T0.plusSeconds(3600);
        register("INC-E1", "alice", "P-1", "T-1", "COMPLETE",
                List.of("ops", "dba", "net"), 2, expiresAt);

        // 第一票：未达法定人数，提案仍 OPEN，任务不变
        VoteView first = decisionService.vote("INC-E1", "P-1", "carol",
                new VoteRequest(key(), "ops", "SUPPORT"));
        assertThat(first.status()).isEqualTo("CAST");
        assertThat(first.taskVersion()).isEqualTo(1);
        assertThat(first.castAt()).isEqualTo(T0);
        DecisionView afterFirst = decisionService.get("INC-E1", "P-1");
        assertThat(afterFirst.status()).isEqualTo("OPEN");
        assertThat(afterFirst.supportCount()).isEqualTo(1);
        assertThat(afterFirst.remainingToQuorum()).isEqualTo(1);
        assertThat(incidentService.getTask("INC-E1", "T-1").status()).isEqualTo("OPEN");

        // 反对票计入统计但不计入法定人数
        decisionService.vote("INC-E1", "P-1", "dave", new VoteRequest(key(), "net", "OPPOSE"));
        DecisionView afterOppose = decisionService.get("INC-E1", "P-1");
        assertThat(afterOppose.opposeCount()).isEqualTo(1);
        assertThat(afterOppose.supportCount()).isEqualTo(1);
        assertThat(afterOppose.status()).isEqualTo("OPEN");

        // 第二票支持：达法定人数 → 生效并原子写入任务变更
        ((ControllableClock) clock).advanceSeconds(10);
        decisionService.vote("INC-E1", "P-1", "erin", new VoteRequest(key(), "dba", "SUPPORT"));
        DecisionView effective = decisionService.get("INC-E1", "P-1");
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.decidedTaskVersion()).isEqualTo(1);
        assertThat(effective.decidedAt()).isEqualTo(T0.plusSeconds(10));
        TaskView task = incidentService.getTask("INC-E1", "T-1");
        assertThat(task.status()).isEqualTo("DONE");
        assertThat(task.doneBy()).isEqualTo("decision:P-1");
        assertThat(task.doneAt()).isEqualTo(T0.plusSeconds(10));

        // 生效后不能再投票、不能撤回
        assertApiStatus(() -> decisionService.vote("INC-E1", "P-1", "frank",
                new VoteRequest(key(), "ops", "SUPPORT")), HttpStatus.CONFLICT);
        assertApiStatus(() -> decisionService.withdraw("INC-E1", "P-1", "carol",
                new VoteWithdrawRequest(key(), "ops")), HttpStatus.CONFLICT);

        // 历史证据完整：三票按投票顺序，票型/角色/版本/时刻固化
        DecisionHistoryView history = decisionService.history("INC-E1", "P-1");
        assertThat(history.votes()).extracting(VoteView::role)
                .containsExactly("ops", "net", "dba");
        assertThat(history.votes()).extracting(VoteView::ballot)
                .containsExactly("SUPPORT", "OPPOSE", "SUPPORT");
        assertThat(history.votes()).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo("CAST");
            assertThat(v.taskVersion()).isEqualTo(1);
        });
    }

    @Test
    void vote_cancelAction_cancelsTask() {
        commanding("INC-E2", "alice");
        createTask("INC-E2", "alice", "T-1");
        register("INC-E2", "alice", "P-1", "T-1", "CANCEL", List.of("ops"), 1,
                T0.plusSeconds(3600));
        decisionService.vote("INC-E2", "P-1", "carol", new VoteRequest(key(), "ops", "SUPPORT"));
        assertThat(decisionService.get("INC-E2", "P-1").status()).isEqualTo("EFFECTIVE");
        TaskView task = incidentService.getTask("INC-E2", "T-1");
        assertThat(task.status()).isEqualTo("CANCELLED");
        assertThat(task.cancelledBy()).isEqualTo("decision:P-1");
    }

    @Test
    void vote_validation() {
        commanding("INC-V2", "alice");
        createTask("INC-V2", "alice", "T-1");
        register("INC-V2", "alice", "P-1", "T-1", "COMPLETE", List.of("ops", "dba"), 2,
                T0.plusSeconds(3600));
        // 角色不在所需集合 → 422，details 返回实际角色与要求集合
        assertThatThrownBy(() -> decisionService.vote("INC-V2", "P-1", "carol",
                new VoteRequest(key(), "guest", "SUPPORT")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details().toString()).contains("guest", "ops", "dba");
                });
        // 同一角色对同一任务版本仅一票 → 409
        decisionService.vote("INC-V2", "P-1", "carol", new VoteRequest(key(), "ops", "SUPPORT"));
        assertApiStatus(() -> decisionService.vote("INC-V2", "P-1", "carol",
                new VoteRequest(key(), "ops", "OPPOSE")), HttpStatus.CONFLICT);
        // 未知票型 → 400；提案不存在 → 404；事件不存在 → 404
        assertApiStatus(() -> decisionService.vote("INC-V2", "P-1", "carol",
                new VoteRequest(key(), "dba", "MAYBE")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> decisionService.vote("INC-V2", "P-9", "carol",
                new VoteRequest(key(), "dba", "SUPPORT")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> decisionService.vote("INC-404", "P-1", "carol",
                new VoteRequest(key(), "dba", "SUPPORT")), HttpStatus.NOT_FOUND);
    }

    @Test
    void vote_afterExpiry_gone410_andProposalExpired() {
        commanding("INC-X1", "alice");
        createTask("INC-X1", "alice", "T-1");
        register("INC-X1", "alice", "P-1", "T-1", "COMPLETE", List.of("ops", "dba"), 2,
                T0.plusSeconds(60));
        String voteKey = key();
        // 到期后投票 → 410，提案转 EXPIRED
        ((ControllableClock) clock).setInstant(T0.plusSeconds(120));
        assertApiStatus(() -> decisionService.vote("INC-X1", "P-1", "carol",
                new VoteRequest(voteKey, "ops", "SUPPORT")), HttpStatus.GONE);
        DecisionView expired = decisionService.get("INC-X1", "P-1");
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.decidedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(expired.decidedTaskVersion()).isNull();
        // 再次投票仍 410；撤回 409；任务未变更
        assertApiStatus(() -> decisionService.vote("INC-X1", "P-1", "carol",
                new VoteRequest(key(), "dba", "SUPPORT")), HttpStatus.GONE);
        assertApiStatus(() -> decisionService.withdraw("INC-X1", "P-1", "carol",
                new VoteWithdrawRequest(key(), "ops")), HttpStatus.CONFLICT);
        assertThat(incidentService.getTask("INC-X1", "T-1").status()).isEqualTo("OPEN");
        // 410 失败不占键：commandKey 未落库，投票未落库
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, voteKey);
        assertThat(keyCount).isZero();
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes", Integer.class);
        assertThat(voteCount).isZero();
    }

    @Test
    void voteWindow_leftClosedRightOpen() {
        commanding("INC-W1", "alice");
        createTask("INC-W1", "alice", "T-1");
        createTask("INC-W1", "alice", "T-2");
        // 窗口 [登记时刻, 到期时刻)：到期前 1 秒可投，到期时刻及之后 410
        register("INC-W1", "alice", "P-1", "T-1", "COMPLETE", List.of("ops", "dba"), 2,
                T0.plusSeconds(60));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(59));
        VoteView ok = decisionService.vote("INC-W1", "P-1", "carol",
                new VoteRequest(key(), "ops", "SUPPORT"));
        assertThat(ok.status()).isEqualTo("CAST");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(60));
        assertApiStatus(() -> decisionService.vote("INC-W1", "P-1", "dave",
                new VoteRequest(key(), "dba", "SUPPORT")), HttpStatus.GONE);
        assertThat(decisionService.get("INC-W1", "P-1").status()).isEqualTo("EXPIRED");
        // 到期时刻等于登记当前时刻允许登记（不早于当前时钟），但窗口为空，投票即 410
        ((ControllableClock) clock).setInstant(T0.plusSeconds(600));
        register("INC-W1", "alice", "P-2", "T-2", "COMPLETE", List.of("ops"), 1,
                T0.plusSeconds(600));
        assertApiStatus(() -> decisionService.vote("INC-W1", "P-2", "carol",
                new VoteRequest(key(), "ops", "SUPPORT")), HttpStatus.GONE);
        assertThat(decisionService.get("INC-W1", "P-2").status()).isEqualTo("EXPIRED");
    }

    @Test
    void withdraw_flow() {
        commanding("INC-W2", "alice");
        createTask("INC-W2", "alice", "T-1");
        register("INC-W2", "alice", "P-1", "T-1", "COMPLETE", List.of("ops", "dba"), 2,
                T0.plusSeconds(3600));
        decisionService.vote("INC-W2", "P-1", "carol", new VoteRequest(key(), "ops", "SUPPORT"));
        assertThat(decisionService.get("INC-W2", "P-1").supportCount()).isEqualTo(1);

        // 撤回：不再计入法定人数，历史保留 WITHDRAWN 证据
        ((ControllableClock) clock).advanceSeconds(5);
        VoteView withdrawn = decisionService.withdraw("INC-W2", "P-1", "carol",
                new VoteWithdrawRequest(key(), "ops"));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.taskVersion()).isEqualTo(1);
        assertThat(withdrawn.castAt()).isEqualTo(T0);
        assertThat(withdrawn.updatedAt()).isEqualTo(T0.plusSeconds(5));
        DecisionView after = decisionService.get("INC-W2", "P-1");
        assertThat(after.supportCount()).isZero();
        assertThat(after.remainingToQuorum()).isEqualTo(2);
        assertThat(decisionService.diagnostics("INC-W2", "P-1").withdrawnCount()).isEqualTo(1);

        // 重复撤回 → 409；无票角色撤回 → 404；撤回后同版本再投 → 409（同一角色同版本仅一票）
        assertApiStatus(() -> decisionService.withdraw("INC-W2", "P-1", "carol",
                new VoteWithdrawRequest(key(), "ops")), HttpStatus.CONFLICT);
        assertApiStatus(() -> decisionService.withdraw("INC-W2", "P-1", "dave",
                new VoteWithdrawRequest(key(), "dba")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> decisionService.vote("INC-W2", "P-1", "carol",
                new VoteRequest(key(), "ops", "SUPPORT")), HttpStatus.CONFLICT);
        // 历史证据不被覆盖：撤回前的票型/时刻保持原值
        DecisionHistoryView history = decisionService.history("INC-W2", "P-1");
        assertThat(history.votes()).hasSize(1);
        assertThat(history.votes().get(0).ballot()).isEqualTo("SUPPORT");
        assertThat(history.votes().get(0).castAt()).isEqualTo(T0);
    }

    @Test
    void taskRevise_stalesOldVotes_notCountedForNewVersion() {
        commanding("INC-R1", "alice");
        createTask("INC-R1", "alice", "T-1");
        register("INC-R1", "alice", "P-1", "T-1", "COMPLETE", List.of("ops", "dba"), 2,
                T0.plusSeconds(3600));
        decisionService.vote("INC-R1", "P-1", "carol", new VoteRequest(key(), "ops", "SUPPORT"));

        // 任务修订：版本 1→2，旧票转 STALE 且不计入新版本
        ((ControllableClock) clock).advanceSeconds(10);
        TaskView revised = incidentService.reviseTask("INC-R1", "T-1", "alice",
                new TaskReviseRequest(key(), "DB", "扩容并调优连接池"));
        assertThat(revised.version()).isEqualTo(2);
        assertThat(revised.title()).isEqualTo("扩容并调优连接池");
        DecisionView afterRevise = decisionService.get("INC-R1", "P-1");
        assertThat(afterRevise.currentTaskVersion()).isEqualTo(2);
        assertThat(afterRevise.supportCount()).isZero();
        assertThat(afterRevise.remainingToQuorum()).isEqualTo(2);
        assertThat(decisionService.diagnostics("INC-R1", "P-1").staleCount()).isEqualTo(1);

        // 历史证据保留：旧票版本/票型/时刻固化
        DecisionHistoryView history = decisionService.history("INC-R1", "P-1");
        assertThat(history.votes()).hasSize(1);
        VoteView stale = history.votes().get(0);
        assertThat(stale.status()).isEqualTo("STALE");
        assertThat(stale.taskVersion()).isEqualTo(1);
        assertThat(stale.ballot()).isEqualTo("SUPPORT");
        assertThat(stale.castAt()).isEqualTo(T0);

        // 同一角色可在新版本重新投票；达法定人数后生效版本固化为 2
        decisionService.vote("INC-R1", "P-1", "carol", new VoteRequest(key(), "ops", "SUPPORT"));
        decisionService.vote("INC-R1", "P-1", "dave", new VoteRequest(key(), "dba", "SUPPORT"));
        DecisionView effective = decisionService.get("INC-R1", "P-1");
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.decidedTaskVersion()).isEqualTo(2);
        assertThat(incidentService.getTask("INC-R1", "T-1").status()).isEqualTo("DONE");
        // 历史：1 张 STALE（v1）+ 2 张 CAST（v2），按投票顺序
        assertThat(decisionService.history("INC-R1", "P-1").votes())
                .extracting(VoteView::status)
                .containsExactly("STALE", "CAST", "CAST");
    }

    @Test
    void taskRevise_validationAndCommandKey() {
        commanding("INC-R2", "alice");
        createTask("INC-R2", "alice", "T-1");
        createTask("INC-R2", "alice", "T-2");
        // 非指挥人 → 409；任务不存在 → 404；终态任务 → 409
        assertApiStatus(() -> incidentService.reviseTask("INC-R2", "T-1", "bob",
                new TaskReviseRequest(key(), "G", "t")), HttpStatus.CONFLICT);
        assertApiStatus(() -> incidentService.reviseTask("INC-R2", "T-9", "alice",
                new TaskReviseRequest(key(), "G", "t")), HttpStatus.NOT_FOUND);
        incidentService.cancelTask("INC-R2", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> incidentService.reviseTask("INC-R2", "T-2", "alice",
                new TaskReviseRequest(key(), "G", "t")), HttpStatus.CONFLICT);
        // 版本递增；同 commandKey 重放首次结果，改参 409
        String reviseKey = key();
        TaskView v2 = incidentService.reviseTask("INC-R2", "T-1", "alice",
                new TaskReviseRequest(reviseKey, "DB", "标题2"));
        TaskView replay = incidentService.reviseTask("INC-R2", "T-1", "alice",
                new TaskReviseRequest(reviseKey, "DB", "标题2"));
        assertThat(replay).isEqualTo(v2);
        assertThat(incidentService.getTask("INC-R2", "T-1").version()).isEqualTo(2);
        assertApiStatus(() -> incidentService.reviseTask("INC-R2", "T-1", "alice",
                new TaskReviseRequest(reviseKey, "DB", "标题3")), HttpStatus.CONFLICT);
        TaskView v3 = incidentService.reviseTask("INC-R2", "T-1", "alice",
                new TaskReviseRequest(key(), "DB", "标题3"));
        assertThat(v3.version()).isEqualTo(3);
    }

    @Test
    void taskRevise_expiresOverdueProposalInsteadOfStaling() {
        commanding("INC-R3", "alice");
        createTask("INC-R3", "alice", "T-1");
        register("INC-R3", "alice", "P-1", "T-1", "COMPLETE", List.of("ops", "dba"), 2,
                T0.plusSeconds(60));
        decisionService.vote("INC-R3", "P-1", "carol", new VoteRequest(key(), "ops", "SUPPORT"));
        // 修订提交时已到期：提案转 EXPIRED 而非 STALE 计票
        ((ControllableClock) clock).setInstant(T0.plusSeconds(120));
        incidentService.reviseTask("INC-R3", "T-1", "alice",
                new TaskReviseRequest(key(), "DB", "新标题"));
        DecisionView expired = decisionService.get("INC-R3", "P-1");
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.decidedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(expired.currentTaskVersion()).isEqualTo(1);
        assertThat(decisionService.diagnostics("INC-R3", "P-1").staleCount()).isZero();
    }

    @Test
    void commandKey_idempotency() {
        commanding("INC-I1", "alice");
        createTask("INC-I1", "alice", "T-1");
        Instant future = T0.plusSeconds(3600);
        // 登记：同键同参重放首次快照；同键改参 409
        String registerKey = key();
        DecisionView first = decisionService.register("INC-I1", "alice",
                new DecisionCreateRequest(registerKey, "P-1", "T-1", "COMPLETE", List.of("ops"), 1,
                        future));
        DecisionView replay = decisionService.register("INC-I1", "alice",
                new DecisionCreateRequest(registerKey, "P-1", "T-1", "COMPLETE", List.of("ops"), 1,
                        future));
        assertThat(replay).isEqualTo(first);
        assertThat(decisionService.list("INC-I1").decisions()).hasSize(1);
        assertApiStatus(() -> decisionService.register("INC-I1", "alice",
                new DecisionCreateRequest(registerKey, "P-2", "T-1", "COMPLETE", List.of("ops"), 1,
                        future)), HttpStatus.CONFLICT);
        // 投票：同键重放首次快照（生效后依然可重放）；失败不占键
        String badVoteKey = key();
        assertApiStatus(() -> decisionService.vote("INC-I1", "P-1", "carol",
                new VoteRequest(badVoteKey, "guest", "SUPPORT")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        VoteView ok = decisionService.vote("INC-I1", "P-1", "carol",
                new VoteRequest(badVoteKey, "ops", "SUPPORT"));
        assertThat(ok.status()).isEqualTo("CAST");
        assertThat(decisionService.get("INC-I1", "P-1").status()).isEqualTo("EFFECTIVE");
        VoteView voteReplay = decisionService.vote("INC-I1", "P-1", "carol",
                new VoteRequest(badVoteKey, "ops", "SUPPORT"));
        assertThat(voteReplay).isEqualTo(ok);
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes", Integer.class);
        assertThat(voteCount).isEqualTo(1);
    }

    @Test
    void expireCheck_andReadsDoNotChangeState() {
        commanding("INC-Q1", "alice");
        createTask("INC-Q1", "alice", "T-1");
        register("INC-Q1", "alice", "P-1", "T-1", "COMPLETE", List.of("ops"), 1,
                T0.plusSeconds(60));
        // 时钟越过到期时刻：只读接口不改变状态
        ((ControllableClock) clock).setInstant(T0.plusSeconds(120));
        assertThat(decisionService.get("INC-Q1", "P-1").status()).isEqualTo("OPEN");
        assertThat(decisionService.list("INC-Q1").decisions().get(0).status()).isEqualTo("OPEN");
        assertThat(decisionService.history("INC-Q1", "P-1").decision().status())
                .isEqualTo("OPEN");
        assertThat(decisionService.diagnostics("INC-Q1", "P-1").status()).isEqualTo("OPEN");
        // 到期判定：提案转 EXPIRED；同键重放首次结果
        String checkKey = key();
        DecisionView checked = decisionService.expireCheck("INC-Q1", "P-1",
                new DecisionExpireCheckRequest(checkKey));
        assertThat(checked.status()).isEqualTo("EXPIRED");
        assertThat(checked.decidedAt()).isEqualTo(T0.plusSeconds(120));
        DecisionView checkedReplay = decisionService.expireCheck("INC-Q1", "P-1",
                new DecisionExpireCheckRequest(checkKey));
        assertThat(checkedReplay).isEqualTo(checked);
        // 未到期时判定保持 OPEN
        commanding("INC-Q2", "bob");
        createTask("INC-Q2", "bob", "T-1");
        register("INC-Q2", "bob", "P-1", "T-1", "COMPLETE", List.of("ops"), 1,
                T0.plusSeconds(3600));
        DecisionView notDue = decisionService.expireCheck("INC-Q2", "P-1",
                new DecisionExpireCheckRequest(key()));
        assertThat(notDue.status()).isEqualTo("OPEN");
    }

    @Test
    void vote_taskTerminalBeforeQuorum_conflictAndNoPartialWrite() {
        commanding("INC-T9", "alice");
        createTask("INC-T9", "alice", "T-1");
        register("INC-T9", "alice", "P-1", "T-1", "COMPLETE", List.of("ops"), 1,
                T0.plusSeconds(3600));
        // 任务被直接完成后，达法定人数的票无法生效 → 409，整事务回滚不留半成品
        incidentService.completeTask("INC-T9", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> decisionService.vote("INC-T9", "P-1", "carol",
                new VoteRequest(key(), "ops", "SUPPORT")), HttpStatus.CONFLICT);
        assertThat(decisionService.get("INC-T9", "P-1").status()).isEqualTo("OPEN");
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes", Integer.class);
        assertThat(voteCount).isZero();
    }
}
