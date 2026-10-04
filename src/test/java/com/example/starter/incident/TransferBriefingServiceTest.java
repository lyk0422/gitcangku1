package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.BriefingRefreshRequest;
import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReadbackRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.BriefingTaskItem;
import com.example.starter.incident.dto.Responses.BriefingView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ReadbackView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 交接简报服务测试：覆盖发起冻结简报、回读确认、失效检测（任务/升级/状态变化）、
 * 刷新版本递增与旧简报保留、403 权限边界与 requestId 幂等语义。
 * 使用可控 Clock，升级相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class TransferBriefingServiceTest {

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
        jdbc.update("DELETE FROM transfer_readbacks");
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

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S1", "核心链路故障", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String taskKey) {
        service.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "G1", "任务 " + taskKey, List.of()));
    }

    private void initiate(String incidentKey, String toCommander) {
        service.initiateTransfer(incidentKey, "alice",
                new TransferRequest(key(), toCommander, "交班说明"));
    }

    private ReadbackView readback(String incidentKey, String reader, long briefingId,
                                  String digest) {
        return service.readbackBriefing(incidentKey, reader,
                new ReadbackRequest(key(), briefingId, digest));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void mainFlow_briefingReadbackAccept_freezesEvidence() {
        commanding("INC-B-001", "alice");
        createTask("INC-B-001", "T1");
        initiate("INC-B-001", "bob");

        BriefingView briefing = service.currentBriefing("INC-B-001", "bob");
        assertThat(briefing.version()).isEqualTo(1);
        assertThat(briefing.status()).isEqualTo("ACTIVE");
        assertThat(briefing.content().incidentStatus()).isEqualTo("COMMANDING");
        assertThat(briefing.content().note()).isEqualTo("交班说明");
        assertThat(briefing.content().unfinishedTasks())
                .extracting(BriefingTaskItem::taskKey).containsExactly("T1");
        assertThat(briefing.readbackBy()).isNull();

        ReadbackView confirmation = readback("INC-B-001", "bob", briefing.id(), briefing.digest());
        assertThat(confirmation.reader()).isEqualTo("bob");
        assertThat(confirmation.version()).isEqualTo(1);

        IncidentView after = service.acceptTransfer("INC-B-001", "bob",
                new TransferAcceptRequest(key(), briefing.id()));
        assertThat(after.commander()).isEqualTo("bob");

        // 交接成功后简报与回读人冻结，旧证据可读
        BriefingView frozen = service.getBriefing("INC-B-001", briefing.id(), "bob");
        assertThat(frozen.status()).isEqualTo("FROZEN");
        assertThat(frozen.readbackBy()).isEqualTo("bob");
        assertThat(frozen.readbackAt()).isNotNull();
        // 交接完成后刷新入口关闭
        assertApiStatus(() -> service.refreshBriefing("INC-B-001", "alice",
                new BriefingRefreshRequest(key(), null)), HttpStatus.CONFLICT);
    }

    @Test
    void staleAfterNewTask_refreshAndReread_thenAccept_oldBriefingKept() {
        commanding("INC-B-002", "alice");
        createTask("INC-B-002", "T1");
        initiate("INC-B-002", "bob");
        BriefingView v1 = service.currentBriefing("INC-B-002", "bob");
        readback("INC-B-002", "bob", v1.id(), v1.digest());

        // 回读含 T1 的简报后新增 T2：简报失效，接收 409，指挥权未切换
        createTask("INC-B-002", "T2");
        assertApiStatus(() -> service.acceptTransfer("INC-B-002", "bob",
                new TransferAcceptRequest(key(), v1.id())), HttpStatus.CONFLICT);
        assertThat(service.get("INC-B-002").commander()).isEqualTo("alice");

        // 交出人刷新产生 v2（含 T1+T2），未提供说明时沿用上一版
        BriefingView v2 = service.refreshBriefing("INC-B-002", "alice",
                new BriefingRefreshRequest(key(), null));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.content().unfinishedTasks())
                .extracting(BriefingTaskItem::taskKey).containsExactly("T1", "T2");
        assertThat(v2.content().note()).isEqualTo("交班说明");
        assertThat(v2.readbackBy()).isNull();

        // 旧回读不能复用：旧版本不能回读也不能用于接收
        assertApiStatus(() -> readback("INC-B-002", "bob", v1.id(), v1.digest()),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.acceptTransfer("INC-B-002", "bob",
                new TransferAcceptRequest(key(), v1.id())), HttpStatus.CONFLICT);

        // 回读 v2 后接收成功
        readback("INC-B-002", "bob", v2.id(), v2.digest());
        IncidentView after = service.acceptTransfer("INC-B-002", "bob",
                new TransferAcceptRequest(key(), v2.id()));
        assertThat(after.commander()).isEqualTo("bob");

        // 旧简报保留且仍只含 T1；最新版已冻结
        BriefingView old = service.getBriefing("INC-B-002", v1.id(), "bob");
        assertThat(old.content().unfinishedTasks())
                .extracting(BriefingTaskItem::taskKey).containsExactly("T1");
        assertThat(service.getBriefing("INC-B-002", v2.id(), "alice").status())
                .isEqualTo("FROZEN");
    }    @Test
    void staleByTaskCompleteCancel_escalationAck_statusChange() {
        // 完成任务使简报失效
        commanding("INC-B-003", "alice");
        createTask("INC-B-003", "T1");
        initiate("INC-B-003", "bob");
        BriefingView b1 = service.currentBriefing("INC-B-003", "bob");
        readback("INC-B-003", "bob", b1.id(), b1.digest());
        service.completeTask("INC-B-003", "T1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.acceptTransfer("INC-B-003", "bob",
                new TransferAcceptRequest(key(), b1.id())), HttpStatus.CONFLICT);

        // 取消任务使简报失效
        commanding("INC-B-004", "alice");
        createTask("INC-B-004", "T1");
        initiate("INC-B-004", "bob");
        BriefingView b2 = service.currentBriefing("INC-B-004", "bob");
        readback("INC-B-004", "bob", b2.id(), b2.digest());
        service.cancelTask("INC-B-004", "T1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.acceptTransfer("INC-B-004", "bob",
                new TransferAcceptRequest(key(), b2.id())), HttpStatus.CONFLICT);

        // 确认升级使简报失效（简报冻结时含一条未确认升级）
        commanding("INC-B-005", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation("INC-B-005", new EscalationCheckRequest(key()));
        initiate("INC-B-005", "bob");
        BriefingView b3 = service.currentBriefing("INC-B-005", "bob");
        assertThat(b3.content().unacknowledgedEscalations()).hasSize(1);
        readback("INC-B-005", "bob", b3.id(), b3.digest());
        service.acknowledgeEscalation("INC-B-005", "alice",
                new EscalationAckRequest(key(), "已处理"));
        assertApiStatus(() -> service.acceptTransfer("INC-B-005", "bob",
                new TransferAcceptRequest(key(), b3.id())), HttpStatus.CONFLICT);

        // 事件状态变更使简报失效
        commanding("INC-B-006", "alice");
        initiate("INC-B-006", "bob");
        BriefingView b4 = service.currentBriefing("INC-B-006", "bob");
        readback("INC-B-006", "bob", b4.id(), b4.digest());
        service.changeStatus("INC-B-006", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> service.acceptTransfer("INC-B-006", "bob",
                new TransferAcceptRequest(key(), b4.id())), HttpStatus.CONFLICT);
    }

    @Test
    void wrongDigest_noConfirmation_andAcceptRequiresReadback() {
        commanding("INC-B-007", "alice");
        initiate("INC-B-007", "bob");
        BriefingView briefing = service.currentBriefing("INC-B-007", "bob");

        // 未回读直接接收 → 409
        assertApiStatus(() -> service.acceptTransfer("INC-B-007", "bob",
                new TransferAcceptRequest(key(), briefing.id())), HttpStatus.CONFLICT);

        // 错误摘要不产生确认，且失败不占幂等键
        String requestId = key();
        String wrongDigest = "0".repeat(64);
        assertApiStatus(() -> service.readbackBriefing("INC-B-007", "bob",
                new ReadbackRequest(requestId, briefing.id(), wrongDigest)), HttpStatus.CONFLICT);
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_readbacks WHERE briefing_id = ?",
                Integer.class, briefing.id());
        assertThat(confirmations).isZero();

        // 同一 requestId 改用正确摘要重试成功（失败未占键）
        ReadbackView ok = service.readbackBriefing("INC-B-007", "bob",
                new ReadbackRequest(requestId, briefing.id(), briefing.digest()));
        assertThat(ok.digest()).isEqualTo(briefing.digest());

        IncidentView after = service.acceptTransfer("INC-B-007", "bob",
                new TransferAcceptRequest(key(), briefing.id()));
        assertThat(after.commander()).isEqualTo("bob");
    }

    @Test
    void permissions_forbiddenBeforeReplay() {
        commanding("INC-B-008", "alice");
        initiate("INC-B-008", "bob");
        BriefingView briefing = service.currentBriefing("INC-B-008", "bob");

        // 交出人/接班人可读，第三人 403
        assertThat(service.currentBriefing("INC-B-008", "alice").id()).isEqualTo(briefing.id());
        assertApiStatus(() -> service.currentBriefing("INC-B-008", "carol"), HttpStatus.FORBIDDEN);
        assertApiStatus(() -> service.getBriefing("INC-B-008", briefing.id(), "carol"),
                HttpStatus.FORBIDDEN);

        // 仅指定接班人可回读：交出人、第三人均 403
        assertApiStatus(() -> readback("INC-B-008", "alice", briefing.id(), briefing.digest()),
                HttpStatus.FORBIDDEN);
        assertApiStatus(() -> readback("INC-B-008", "carol", briefing.id(), briefing.digest()),
                HttpStatus.FORBIDDEN);

        // 仅交出人可刷新：接班人、第三人 403
        assertApiStatus(() -> service.refreshBriefing("INC-B-008", "bob",
                new BriefingRefreshRequest(key(), null)), HttpStatus.FORBIDDEN);
        assertApiStatus(() -> service.refreshBriefing("INC-B-008", "carol",
                new BriefingRefreshRequest(key(), null)), HttpStatus.FORBIDDEN);

        // 权限先于重放：接班人已占用的 requestId，第三人使用得到 403 而非重放冲突
        String requestId = key();
        service.readbackBriefing("INC-B-008", "bob",
                new ReadbackRequest(requestId, briefing.id(), briefing.digest()));
        assertApiStatus(() -> service.readbackBriefing("INC-B-008", "carol",
                new ReadbackRequest(requestId, briefing.id(), briefing.digest())),
                HttpStatus.FORBIDDEN);
    }    @Test
    void requestId_idempotency_replayAndConflict() {
        commanding("INC-B-009", "alice");
        initiate("INC-B-009", "bob");
        BriefingView briefing = service.currentBriefing("INC-B-009", "bob");

        // 同 requestId 同参重放首次完整响应，确认只写一次
        String requestId = key();
        ReadbackView first = service.readbackBriefing("INC-B-009", "bob",
                new ReadbackRequest(requestId, briefing.id(), briefing.digest()));
        ReadbackView replay = service.readbackBriefing("INC-B-009", "bob",
                new ReadbackRequest(requestId, briefing.id(), briefing.digest()));
        assertThat(replay).isEqualTo(first);
        Integer readbackCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_readbacks WHERE briefing_id = ?",
                Integer.class, briefing.id());
        assertThat(readbackCount).isEqualTo(1);

        // 同 requestId 异参 → 409
        assertApiStatus(() -> service.readbackBriefing("INC-B-009", "bob",
                new ReadbackRequest(requestId, briefing.id(), "f".repeat(64))),
                HttpStatus.CONFLICT);

        // 刷新幂等：同 requestId 同参重放只产生一个新版本
        String refreshId = key();
        BriefingView v2 = service.refreshBriefing("INC-B-009", "alice",
                new BriefingRefreshRequest(refreshId, "新说明"));
        BriefingView v2Replay = service.refreshBriefing("INC-B-009", "alice",
                new BriefingRefreshRequest(refreshId, "新说明"));
        assertThat(v2Replay).isEqualTo(v2);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.content().note()).isEqualTo("新说明");
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_briefings WHERE transfer_id = ?",
                Integer.class, briefing.transferId());
        assertThat(versions).isEqualTo(2);
        // 同 requestId 异参 → 409
        assertApiStatus(() -> service.refreshBriefing("INC-B-009", "alice",
                new BriefingRefreshRequest(refreshId, "另一说明")), HttpStatus.CONFLICT);

        // 接受幂等：同 commandKey 同参重放首次响应，交接只生效一次
        readback("INC-B-009", "bob", v2.id(), v2.digest());
        String acceptKey = key();
        IncidentView accepted = service.acceptTransfer("INC-B-009", "bob",
                new TransferAcceptRequest(acceptKey, v2.id()));
        IncidentView acceptedReplay = service.acceptTransfer("INC-B-009", "bob",
                new TransferAcceptRequest(acceptKey, v2.id()));
        assertThat(acceptedReplay).isEqualTo(accepted);
        assertThat(service.history("INC-B-009").transfers()).hasSize(1);
        assertThat(service.get("INC-B-009").commander()).isEqualTo("bob");
    }
}