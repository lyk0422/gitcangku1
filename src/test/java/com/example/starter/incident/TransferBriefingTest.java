package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferReadbackRequest;
import com.example.starter.incident.dto.Requests.TransferRefreshRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.BriefingView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 交接简报服务测试：覆盖简报生成与冻结内容、回读权限与摘要校验、
 * 失效条件（任务/升级/状态变更）、刷新版本规则、接收门禁与幂等语义。
 * 使用可控 Clock，升级相关场景不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class TransferBriefingTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM transfer_briefings");
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

    private IncidentView setup(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "支付网关超时", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void initiate(String ik, String from, String to) {
        service.initiateTransfer(ik, from, new TransferRequest(key(), to, "交班说明"));
    }

    private BriefingView current(String ik, String actor) {
        return service.currentBriefing(ik, actor);
    }

    private BriefingView readback(String ik, String actor, BriefingView briefing) {
        return service.readbackBriefing(ik, actor,
                new TransferReadbackRequest(key(), briefing.id(), briefing.digest()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void initiate_createsBriefingV1_withFrozenContent() {
        String ik = "INC-B-001";
        setup(ik, "alice");
        service.createTask(ik, "alice", new TaskCreateRequest(key(), "T1", "G1", "扩容", List.of()));
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob", "注意阻塞项"));

        BriefingView b = current(ik, "bob");
        assertThat(b.version()).isEqualTo(1);
        assertThat(b.status()).isEqualTo("CURRENT");
        assertThat(b.note()).isEqualTo("注意阻塞项");
        assertThat(b.incidentStatus()).isEqualTo("COMMANDING");
        assertThat(b.unfinishedTasks()).hasSize(1);
        assertThat(b.unfinishedTasks().get(0).taskKey()).isEqualTo("T1");
        assertThat(b.unfinishedTasks().get(0).status()).isEqualTo("OPEN");
        assertThat(b.openEscalations()).isEmpty();
        assertThat(b.digest()).hasSize(64);
        assertThat(b.createdBy()).isEqualTo("alice");
        assertThat(b.readbackBy()).isNull();
        // 冻结的事件版本与库中当前版本一致
        Long version = jdbc.queryForObject(
                "SELECT version FROM incidents WHERE incident_key = ?", Long.class, ik);
        assertThat(b.incidentVersion()).isEqualTo(version);
    }

    @Test
    void briefing_permissionBoundaries_forbiddenForOutsiders() {
        String ik = "INC-B-002";
        setup(ik, "alice");
        initiate(ik, "alice", "bob");
        BriefingView b = current(ik, "bob");

        // 交出人可读，第三方读当前简报 403
        assertThat(current(ik, "alice").id()).isEqualTo(b.id());
        assertApiStatus(() -> service.currentBriefing(ik, "mallory"), HttpStatus.FORBIDDEN);
        // 第三方读指定简报 403
        assertApiStatus(() -> service.getBriefing(ik, b.id(), "mallory"), HttpStatus.FORBIDDEN);
        assertThat(service.getBriefing(ik, b.id(), "alice").id()).isEqualTo(b.id());
        // 第三方与交出人回读均 403（仅指定接班人）
        assertApiStatus(() -> service.readbackBriefing(ik, "mallory",
                new TransferReadbackRequest(key(), b.id(), b.digest())), HttpStatus.FORBIDDEN);
        assertApiStatus(() -> service.readbackBriefing(ik, "alice",
                new TransferReadbackRequest(key(), b.id(), b.digest())), HttpStatus.FORBIDDEN);
        // 第三方与接班人刷新均 403（仅交出人）
        assertApiStatus(() -> service.refreshBriefing(ik, "mallory",
                new TransferRefreshRequest(key(), 1L, null)), HttpStatus.FORBIDDEN);
        assertApiStatus(() -> service.refreshBriefing(ik, "bob",
                new TransferRefreshRequest(key(), 1L, null)), HttpStatus.FORBIDDEN);
    }
    @Test
    void readback_wrongDigest_noConfirmation_andFailureDoesNotHoldKey() {
        String ik = "INC-B-003";
        setup(ik, "alice");
        initiate(ik, "alice", "bob");
        BriefingView b = current(ik, "bob");

        // 错误摘要：409 且不产生确认
        String requestId = key();
        assertApiStatus(() -> service.readbackBriefing(ik, "bob",
                new TransferReadbackRequest(requestId, b.id(), "0".repeat(64))),
                HttpStatus.CONFLICT);
        assertThat(current(ik, "bob").readbackBy()).isNull();

        // 失败不占键：同 requestId 换正确摘要可成功
        BriefingView ok = service.readbackBriefing(ik, "bob",
                new TransferReadbackRequest(requestId, b.id(), b.digest()));
        assertThat(ok.readbackBy()).isEqualTo("bob");
        assertThat(ok.readbackAt()).isNotNull();

        // 同键同参重放首次完整响应
        BriefingView replay = service.readbackBriefing(ik, "bob",
                new TransferReadbackRequest(requestId, b.id(), b.digest()));
        assertThat(replay).isEqualTo(ok);
        // 同键异参 409
        assertApiStatus(() -> service.readbackBriefing(ik, "bob",
                new TransferReadbackRequest(requestId, b.id(), "1".repeat(64))),
                HttpStatus.CONFLICT);
        // 已确认后用新键重复回读 409
        assertApiStatus(() -> readback(ik, "bob", b), HttpStatus.CONFLICT);
    }

    @Test
    void accept_requiresReadback_andFreezesBriefingAtomically() {
        String ik = "INC-B-004";
        setup(ik, "alice");
        service.createTask(ik, "alice", new TaskCreateRequest(key(), "T1", "G1", "扩容", List.of()));
        initiate(ik, "alice", "bob");
        BriefingView b = current(ik, "bob");

        // 未回读直接接收 409；briefingId 缺失 400
        assertApiStatus(() -> service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), b.id())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.acceptTransfer(ik, "bob", new TransferAcceptRequest(key())),
                HttpStatus.BAD_REQUEST);

        // 回读后接收成功：指挥权切换与简报冻结原子完成
        readback(ik, "bob", b);
        IncidentView after = service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), b.id()));
        assertThat(after.commander()).isEqualTo("bob");

        BriefingView frozen = service.getBriefing(ik, b.id(), "bob");
        assertThat(frozen.status()).isEqualTo("FROZEN");
        assertThat(frozen.readbackBy()).isEqualTo("bob");
        assertThat(frozen.frozenAt()).isNotNull();
        assertThat(frozen.unfinishedTasks()).extracting("taskKey").containsExactly("T1");
    }

    @Test
    void accept_staleAfterNewTask_refreshThenSucceed_oldBriefingKept() {
        String ik = "INC-B-005";
        setup(ik, "alice");
        service.createTask(ik, "alice", new TaskCreateRequest(key(), "T1", "G1", "扩容", List.of()));
        initiate(ik, "alice", "bob");
        BriefingView v1 = current(ik, "bob");
        readback(ik, "bob", v1);

        // 回读含 T1 的简报后新增 T2：接收 409（简报失效）
        service.createTask(ik, "alice", new TaskCreateRequest(key(), "T2", "G1", "限流", List.of()));
        assertApiStatus(() -> service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), v1.id())), HttpStatus.CONFLICT);

        // 交出人刷新：v2 含 T1+T2，旧回读不能复用
        BriefingView v2 = service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(key(), 1L, null));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.unfinishedTasks()).extracting("taskKey").containsExactly("T1", "T2");
        assertApiStatus(() -> service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), v2.id())), HttpStatus.CONFLICT);
        // 旧简报已 SUPERSEDED：回读 v1 或引用 v1 接收均 409
        assertApiStatus(() -> readback(ik, "bob", v1), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), v1.id())), HttpStatus.CONFLICT);

        // 回读 v2 后接收成功
        readback(ik, "bob", v2);
        IncidentView after = service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), v2.id()));
        assertThat(after.commander()).isEqualTo("bob");

        // 旧简报保留且仍只含 T1
        BriefingView old = service.getBriefing(ik, v1.id(), "alice");
        assertThat(old.status()).isEqualTo("SUPERSEDED");
        assertThat(old.unfinishedTasks()).extracting("taskKey").containsExactly("T1");
    }
    @Test
    void accept_invalidatedByTaskCompleteCancelAndStatusChange() {
        // 完成任务使简报失效
        String ik1 = "INC-B-011";
        setup(ik1, "alice");
        service.createTask(ik1, "alice", new TaskCreateRequest(key(), "T1", "G", "t", List.of()));
        initiate(ik1, "alice", "bob");
        BriefingView b1 = current(ik1, "bob");
        readback(ik1, "bob", b1);
        service.completeTask(ik1, "T1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.acceptTransfer(ik1, "bob",
                new TransferAcceptRequest(key(), b1.id())), HttpStatus.CONFLICT);

        // 取消任务使简报失效
        String ik2 = "INC-B-012";
        setup(ik2, "alice");
        service.createTask(ik2, "alice", new TaskCreateRequest(key(), "T1", "G", "t", List.of()));
        initiate(ik2, "alice", "bob");
        BriefingView b2 = current(ik2, "bob");
        readback(ik2, "bob", b2);
        service.cancelTask(ik2, "T1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.acceptTransfer(ik2, "bob",
                new TransferAcceptRequest(key(), b2.id())), HttpStatus.CONFLICT);

        // 改变事件状态使简报失效
        String ik3 = "INC-B-013";
        setup(ik3, "alice");
        initiate(ik3, "alice", "bob");
        BriefingView b3 = current(ik3, "bob");
        readback(ik3, "bob", b3);
        service.changeStatus(ik3, "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> service.acceptTransfer(ik3, "bob",
                new TransferAcceptRequest(key(), b3.id())), HttpStatus.CONFLICT);
    }

    @Test
    void accept_invalidatedByEscalationAck_briefingFrozenOpenEscalation() {
        String ik = "INC-B-014";
        service.report(new ReportRequest(ik, "S1", "核心链路故障", "reporter-1"));
        service.takeover(ik, "alice", new TakeoverRequest(key()));
        // 推进到遏制期限之后触发 OPEN 升级记录
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(ik, new EscalationCheckRequest(key()));

        initiate(ik, "alice", "bob");
        BriefingView b = current(ik, "bob");
        // 简报冻结了未确认升级记录
        assertThat(b.openEscalations()).hasSize(1);
        readback(ik, "bob", b);

        // 确认升级使简报失效
        service.acknowledgeEscalation(ik, "alice", new EscalationAckRequest(key(), "已通知值班长"));
        assertApiStatus(() -> service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), b.id())), HttpStatus.CONFLICT);

        // 刷新后未确认升级清单为空，回读新简报可接收
        BriefingView v2 = service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(key(), 1L, null));
        assertThat(v2.openEscalations()).isEmpty();
        readback(ik, "bob", v2);
        IncidentView after = service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(key(), v2.id()));
        assertThat(after.commander()).isEqualTo("bob");
    }

    @Test
    void refresh_versionRulesAndIdempotentReplay() {
        String ik = "INC-B-015";
        setup(ik, "alice");
        initiate(ik, "alice", "bob");

        // 期望版本不匹配 409
        assertApiStatus(() -> service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(key(), 5L, null)), HttpStatus.CONFLICT);
        // note 为空沿用上一版说明
        BriefingView v2 = service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(key(), 1L, null));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.note()).isEqualTo("交班说明");
        // note 非空更新说明
        BriefingView v3 = service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(key(), 2L, "新说明"));
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.note()).isEqualTo("新说明");

        // 同 requestId 同参重放首次响应，且只刷新一次
        String requestId = key();
        BriefingView first = service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(requestId, 3L, "n3"));
        BriefingView replay = service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(requestId, 3L, "n3"));
        assertThat(replay).isEqualTo(first);
        assertThat(current(ik, "alice").version()).isEqualTo(4);
        // 同键异参 409
        assertApiStatus(() -> service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(requestId, 3L, "other")), HttpStatus.CONFLICT);
    }

    @Test
    void accept_commandKeyReplay_returnsFirstResponse() {
        String ik = "INC-B-016";
        setup(ik, "alice");
        initiate(ik, "alice", "bob");
        BriefingView b = current(ik, "bob");
        readback(ik, "bob", b);

        String commandKey = key();
        IncidentView first = service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(commandKey, b.id()));
        IncidentView replay = service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(commandKey, b.id()));
        assertThat(replay).isEqualTo(first);
        assertThat(service.get(ik).commander()).isEqualTo("bob");
        // 同键异参（不同 briefingId）409
        assertApiStatus(() -> service.acceptTransfer(ik, "bob",
                new TransferAcceptRequest(commandKey, b.id() + 1000)), HttpStatus.CONFLICT);
    }

    @Test
    void readback_permissionCheckedBeforeReplay_forbiddenDoesNotHoldKey() {
        String ik = "INC-B-017";
        setup(ik, "alice");
        initiate(ik, "alice", "bob");
        BriefingView b = current(ik, "bob");

        // 第三方以该 requestId 回读：403（权限先于重放），且不占键
        String requestId = key();
        assertApiStatus(() -> service.readbackBriefing(ik, "mallory",
                new TransferReadbackRequest(requestId, b.id(), b.digest())), HttpStatus.FORBIDDEN);
        // 接班人用同一 requestId 正常回读成功
        BriefingView ok = service.readbackBriefing(ik, "bob",
                new TransferReadbackRequest(requestId, b.id(), b.digest()));
        assertThat(ok.readbackBy()).isEqualTo("bob");
    }

    @Test
    void digest_fieldBoundarySafe_withDelimiterLikeNote() {
        String ik = "INC-B-018";
        setup(ik, "alice");
        // 补充说明含分隔符与 JSON 结构字符，摘要仍由结构化内容计算，字段边界不可碰撞
        String tricky = "ab\",\"taskKey\":\"T9";
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob", tricky));
        BriefingView b = current(ik, "bob");
        assertThat(b.note()).isEqualTo(tricky);
        assertThat(b.digest()).hasSize(64);

        // 正确摘要可回读；内容不同的简报摘要不同
        BriefingView ok = readback(ik, "bob", b);
        assertThat(ok.readbackBy()).isEqualTo("bob");
        BriefingView v2 = service.refreshBriefing(ik, "alice",
                new TransferRefreshRequest(key(), 1L, "a"));
        assertThat(v2.digest()).isNotEqualTo(b.digest());
    }
}