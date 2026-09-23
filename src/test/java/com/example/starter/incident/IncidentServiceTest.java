package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ActionView;
import com.example.starter.incident.dto.Responses.HistoryView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TransferView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 事件指挥服务测试：覆盖主流程、失败分支、状态机边界与幂等语义。
 */
@SpringBootTest
class IncidentServiceTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM proposal_votes");
        jdbc.update("DELETE FROM proposal_roster_entries");
        jdbc.update("DELETE FROM dependency_change_proposals");
        jdbc.update("DELETE FROM incident_dependency_edges");
        jdbc.update("UPDATE dependency_graph_meta SET graph_version = 1");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "INC-" + UUID.randomUUID();
    }

    private IncidentView reportIncident(String incidentKey) {
        return service.report(new ReportRequest(incidentKey, "S2", "数据库连接池耗尽", "reporter-1"));
    }

    private IncidentView commandingIncident(String incidentKey, String commander) {
        reportIncident(incidentKey);
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void fullLifecycle_reportToClose() {
        String ik = "INC-100";
        IncidentView reported = reportIncident(ik);
        assertThat(reported.status()).isEqualTo("REPORTED");
        assertThat(reported.commander()).isNull();

        IncidentView commanding = service.takeover(ik, "alice", new TakeoverRequest(key()));
        assertThat(commanding.status()).isEqualTo("COMMANDING");
        assertThat(commanding.commander()).isEqualTo("alice");

        ActionView action = service.addAction(ik, "alice", new ActionRequest(key(), "ACT-1",
                "MITIGATE", "扩容连接池", Instant.parse("2026-09-21T08:00:00Z")));
        assertThat(action.actor()).isEqualTo("alice");

        IncidentView contained = service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(contained.status()).isEqualTo("CONTAINED");

        IncidentView resolved = service.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");

        IncidentView closed = service.changeStatus(ik, "alice", new StatusRequest(key(), "CLOSED"));
        assertThat(closed.status()).isEqualTo("CLOSED");

        HistoryView history = service.history(ik);
        assertThat(history.statusHistory()).extracting(s -> s.toStatus())
                .containsExactly("REPORTED", "COMMANDING", "CONTAINED", "RESOLVED", "CLOSED");
        assertThat(history.actions()).hasSize(1);
        assertThat(history.transfers()).isEmpty();
    }

    @Test
    void report_duplicateIncidentKey_conflict() {
        reportIncident("INC-101");
        assertApiStatus(() -> reportIncident("INC-101"), HttpStatus.CONFLICT);
    }

    @Test
    void report_invalidParams_badRequest() {
        assertApiStatus(() -> service.report(new ReportRequest("INC-102", "S9", "x", "r")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.report(new ReportRequest(" ", "S1", "x", "r")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.report(new ReportRequest("INC-103", "S1", " ", "r")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.report(new ReportRequest("INC-104", "S1", "x", null)),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void takeover_onlyFromReported_illegalTransition() {
        commandingIncident("INC-110", "alice");
        assertApiStatus(() -> service.takeover("INC-110", "bob", new TakeoverRequest(key())),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void takeover_idempotentReplay_sameKeySameParams() {
        reportIncident("INC-111");
        String commandKey = key();
        IncidentView first = service.takeover("INC-111", "alice", new TakeoverRequest(commandKey));
        IncidentView replay = service.takeover("INC-111", "alice", new TakeoverRequest(commandKey));
        assertThat(replay).isEqualTo(first);
        assertThat(service.get("INC-111").commander()).isEqualTo("alice");
    }

    @Test
    void takeover_sameKeyDifferentParams_conflict() {
        reportIncident("INC-112");
        String commandKey = key();
        service.takeover("INC-112", "alice", new TakeoverRequest(commandKey));
        reportIncident("INC-113");
        assertApiStatus(() -> service.takeover("INC-113", "alice", new TakeoverRequest(commandKey)),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.takeover("INC-112", "bob", new TakeoverRequest(commandKey)),
                HttpStatus.CONFLICT);
    }

    @Test
    void action_requiresCurrentCommander() {
        reportIncident("INC-120");
        // 未接管时无人可写
        assertApiStatus(() -> service.addAction("INC-120", "alice", new ActionRequest(key(), "A1",
                "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))), HttpStatus.CONFLICT);
        service.takeover("INC-120", "alice", new TakeoverRequest(key()));
        // 非当前指挥人不可写
        assertApiStatus(() -> service.addAction("INC-120", "bob", new ActionRequest(key(), "A1",
                "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))), HttpStatus.CONFLICT);
    }

    @Test
    void action_actionKeyIdempotency() {
        commandingIncident("INC-121", "alice");
        Instant at = Instant.parse("2026-09-21T08:00:00Z");
        ActionView first = service.addAction("INC-121", "alice",
                new ActionRequest(key(), "ACT-1", "NOTE", "首次说明", at));
        // 不同 commandKey、同 actionKey 同内容：幂等返回首次记录
        ActionView again = service.addAction("INC-121", "alice",
                new ActionRequest(key(), "ACT-1", "NOTE", "首次说明", at));
        assertThat(again).isEqualTo(first);
        assertThat(service.history("INC-121").actions()).hasSize(1);
        // 同 actionKey 不同内容：409
        assertApiStatus(() -> service.addAction("INC-121", "alice",
                new ActionRequest(key(), "ACT-1", "NOTE", "不同说明", at)), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.addAction("INC-121", "alice",
                new ActionRequest(key(), "ACT-1", "ESCALATE", "首次说明", at)), HttpStatus.CONFLICT);
    }

    @Test
    void action_afterClosed_illegalTransition() {
        commandingIncident("INC-122", "alice");
        service.changeStatus("INC-122", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-122", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-122", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> service.addAction("INC-122", "alice", new ActionRequest(key(), "A1",
                "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void statusChange_mustStepForward() {
        commandingIncident("INC-130", "alice");
        // 跳级
        assertApiStatus(() -> service.changeStatus("INC-130", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.changeStatus("INC-130", "alice",
                new StatusRequest(key(), "CLOSED")), HttpStatus.UNPROCESSABLE_ENTITY);
        // 回退与未知状态
        service.changeStatus("INC-130", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> service.changeStatus("INC-130", "alice",
                new StatusRequest(key(), "COMMANDING")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.changeStatus("INC-130", "alice",
                new StatusRequest(key(), "UNKNOWN")), HttpStatus.BAD_REQUEST);
        // 未解决不能关闭
        assertApiStatus(() -> service.changeStatus("INC-130", "alice",
                new StatusRequest(key(), "CLOSED")), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void statusChange_requiresCurrentCommander() {
        commandingIncident("INC-131", "alice");
        assertApiStatus(() -> service.changeStatus("INC-131", "bob",
                new StatusRequest(key(), "CONTAINED")), HttpStatus.CONFLICT);
    }

    @Test
    void transfer_twoStepHandshake() {
        commandingIncident("INC-140", "alice");
        // 非指挥人不能发起
        assertApiStatus(() -> service.initiateTransfer("INC-140", "bob",
                new TransferRequest(key(), "carol")), HttpStatus.CONFLICT);
        // 目标人不能是自己
        assertApiStatus(() -> service.initiateTransfer("INC-140", "alice",
                new TransferRequest(key(), "alice")), HttpStatus.BAD_REQUEST);

        TransferView pending = service.initiateTransfer("INC-140", "alice",
                new TransferRequest(key(), "bob"));
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(service.get("INC-140").pendingTransferTo()).isEqualTo("bob");

        // 待接受期间目标人不能操作事件
        assertApiStatus(() -> service.addAction("INC-140", "bob", new ActionRequest(key(), "A1",
                "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))), HttpStatus.CONFLICT);
        // 非目标人不能接受
        assertApiStatus(() -> service.acceptTransfer("INC-140", "carol",
                new TransferAcceptRequest(key())), HttpStatus.CONFLICT);
        // 已有待接受交接时不能重复发起
        assertApiStatus(() -> service.initiateTransfer("INC-140", "alice",
                new TransferRequest(key(), "carol")), HttpStatus.CONFLICT);

        // 目标人接受后原子切换
        IncidentView after = service.acceptTransfer("INC-140", "bob", new TransferAcceptRequest(key()));
        assertThat(after.commander()).isEqualTo("bob");
        assertThat(service.get("INC-140").pendingTransferTo()).isNull();

        // 旧指挥人失去权限，新指挥人接管
        assertApiStatus(() -> service.addAction("INC-140", "alice", new ActionRequest(key(), "A2",
                "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))), HttpStatus.CONFLICT);
        ActionView byNew = service.addAction("INC-140", "bob", new ActionRequest(key(), "A2",
                "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z")));
        assertThat(byNew.actor()).isEqualTo("bob");

        HistoryView history = service.history("INC-140");
        assertThat(history.transfers()).hasSize(1);
        assertThat(history.transfers().get(0).status()).isEqualTo("ACCEPTED");
        assertThat(history.transfers().get(0).acceptedAt()).isNotNull();
    }

    @Test
    void transfer_forbiddenWhenResolvedOrClosed() {
        commandingIncident("INC-141", "alice");
        service.changeStatus("INC-141", "alice", new StatusRequest(key(), "CONTAINED"));
        // CONTAINED 下可发起，随后 RESOLVED 后不能接受
        service.initiateTransfer("INC-141", "alice", new TransferRequest(key(), "bob"));
        service.changeStatus("INC-141", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> service.acceptTransfer("INC-141", "bob",
                new TransferAcceptRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.initiateTransfer("INC-141", "alice",
                new TransferRequest(key(), "carol")), HttpStatus.UNPROCESSABLE_ENTITY);
        service.changeStatus("INC-141", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> service.initiateTransfer("INC-141", "alice",
                new TransferRequest(key(), "carol")), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void accept_withoutPending_conflict() {
        commandingIncident("INC-142", "alice");
        assertApiStatus(() -> service.acceptTransfer("INC-142", "bob",
                new TransferAcceptRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void getAndHistory_notFound() {
        assertApiStatus(() -> service.get("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.history("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.takeover("INC-404", "alice", new TakeoverRequest(key())),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void commandKey_replayAcrossOperations_conflict() {
        commandingIncident("INC-150", "alice");
        String commandKey = key();
        service.changeStatus("INC-150", "alice", new StatusRequest(commandKey, "CONTAINED"));
        // 同键用于不同操作 → 409
        assertApiStatus(() -> service.addAction("INC-150", "alice", new ActionRequest(commandKey,
                "A1", "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))), HttpStatus.CONFLICT);
        // 同操作同键同参 → 重放
        IncidentView replay = service.changeStatus("INC-150", "alice",
                new StatusRequest(commandKey, "CONTAINED"));
        assertThat(replay.status()).isEqualTo("CONTAINED");
        assertThat(service.history("INC-150").statusHistory())
                .filteredOn(s -> "CONTAINED".equals(s.toStatus())).hasSize(1);
    }
}
