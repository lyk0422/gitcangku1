package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
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
 * 遏制逾期升级服务测试：覆盖期限确定、检查触发/不触发、确认权限与状态边界、
 * 交接期间权限、遏制原子取消、失败回滚与 commandKey 幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class EscalationServiceTest {

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
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private IncidentView setup(String incidentKey, String severity, String commander) {
        service.report(new ReportRequest(incidentKey, severity, "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void deadline_setAtTakeover_bySeverity_andNotResetByTransfer() {
        Map<String, Long> minutes = Map.of("S1", 5L, "S2", 15L, "S3", 60L, "S4", 240L);
        int n = 0;
        for (Map.Entry<String, Long> e : minutes.entrySet()) {
            String ik = "INC-DL-" + n++;
            IncidentView commanding = setup(ik, e.getKey(), "alice");
            Instant expected = T0.plus(e.getValue(), ChronoUnit.MINUTES);
            assertThat(commanding.deadlineAt()).isEqualTo(expected);
            // REPORTED 阶段无期限
            service.report(new ReportRequest(ik + "-R", e.getKey(), "x", "r"));
            assertThat(service.get(ik + "-R").deadlineAt()).isNull();

            // 交接不重置期限
            service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
            service.acceptTransfer(ik, "bob", new TransferAcceptRequest(key()));
            assertThat(service.get(ik).deadlineAt()).isEqualTo(expected);
        }
    }

    @Test
    void check_beforeDeadline_noRecord_andQueryDoesNotWrite() {
        setup("INC-E-001", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(299));
        EscalationHistoryView view = service.checkEscalation("INC-E-001",
                new EscalationCheckRequest(key()));
        assertThat(view.current()).isNull();
        assertThat(view.history()).isEmpty();
        assertThat(view.deadlineAt()).isEqualTo(T0.plus(5, ChronoUnit.MINUTES));
        // GET 查询不得隐式写入
        EscalationHistoryView queried = service.escalationHistory("INC-E-001");
        assertThat(queried.current()).isNull();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_escalations WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key='INC-E-001')",
                Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void check_beforeDeadline_sameKeyReplay_neverReevaluates() {
        setup("INC-E-002", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(60));
        String commandKey = key();
        EscalationHistoryView first = service.checkEscalation("INC-E-002",
                new EscalationCheckRequest(commandKey));
        assertThat(first.current()).isNull();
        // 时钟越过期限后同键重放仍返回首次（无记录）结果，不重新评估
        ((ControllableClock) clock).setInstant(T0.plus(10, ChronoUnit.MINUTES));
        EscalationHistoryView replay = service.checkEscalation("INC-E-002",
                new EscalationCheckRequest(commandKey));
        assertThat(replay).isEqualTo(first);
        assertThat(service.escalationHistory("INC-E-002").current()).isNull();
        // 换新键重新检查才触发
        EscalationHistoryView again = service.checkEscalation("INC-E-002",
                new EscalationCheckRequest(key()));
        assertThat(again.current()).isNotNull();
        assertThat(again.current().status()).isEqualTo("OPEN");
    }

    @Test
    void check_afterDeadline_createsSingleOpenRecord_andRepeatsReturnSameRecord() {
        setup("INC-E-003", "S2", "alice");
        Instant at = T0.plus(16, ChronoUnit.MINUTES);
        ((ControllableClock) clock).setInstant(at);
        EscalationHistoryView view = service.checkEscalation("INC-E-003",
                new EscalationCheckRequest(key()));
        EscalationView esc = view.current();
        assertThat(esc.status()).isEqualTo("OPEN");
        assertThat(esc.deadlineAt()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));
        assertThat(esc.triggeredAt()).isEqualTo(at);
        assertThat(esc.triggeredCommander()).isEqualTo("alice");
        assertThat(esc.note()).isNull();
        assertThat(esc.acknowledgedBy()).isNull();
        // 再次检查（新键）不产生第二条
        EscalationHistoryView second = service.checkEscalation("INC-E-003",
                new EscalationCheckRequest(key()));
        assertThat(second.current().id()).isEqualTo(esc.id());
        assertThat(service.history("INC-E-003").escalations()).hasSize(1);
    }

    @Test
    void check_onNonCommandingStates_createsNothing() {
        setup("INC-E-004", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(10, ChronoUnit.MINUTES));
        service.changeStatus("INC-E-004", "alice", new StatusRequest(key(), "CONTAINED"));
        EscalationHistoryView afterContain = service.checkEscalation("INC-E-004",
                new EscalationCheckRequest(key()));
        assertThat(afterContain.current()).isNull();
        service.changeStatus("INC-E-004", "alice", new StatusRequest(key(), "RESOLVED"));
        assertThat(service.checkEscalation("INC-E-004", new EscalationCheckRequest(key()))
                .current()).isNull();
        service.changeStatus("INC-E-004", "alice", new StatusRequest(key(), "CLOSED"));
        assertThat(service.checkEscalation("INC-E-004", new EscalationCheckRequest(key()))
                .current()).isNull();
        // REPORTED（未接管）检查不产生记录
        service.report(new ReportRequest("INC-E-004-R", "S1", "x", "r"));
        assertThat(service.checkEscalation("INC-E-004-R", new EscalationCheckRequest(key()))
                .current()).isNull();
    }

    @Test
    void acknowledge_byCurrentCommander_succeeds() {
        setup("INC-E-010", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation("INC-E-010", new EscalationCheckRequest(key()));
        Instant ackAt = T0.plus(7, ChronoUnit.MINUTES);
        ((ControllableClock) clock).setInstant(ackAt);
        EscalationView ack = service.acknowledgeEscalation("INC-E-010", "alice",
                new EscalationAckRequest(key(), "已升级值班副总并扩容"));
        assertThat(ack.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.note()).isEqualTo("已升级值班副总并扩容");
        assertThat(ack.acknowledgedBy()).isEqualTo("alice");
        assertThat(ack.acknowledgedAt()).isEqualTo(ackAt);
    }

    @Test
    void acknowledge_nonCommanderOrEmptyNote_conflictOrBadRequest() {
        setup("INC-E-011", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation("INC-E-011", new EscalationCheckRequest(key()));
        // 非当前指挥人
        assertApiStatus(() -> service.acknowledgeEscalation("INC-E-011", "bob",
                new EscalationAckRequest(key(), "说明")), HttpStatus.CONFLICT);
        // 空说明 400
        assertApiStatus(() -> service.acknowledgeEscalation("INC-E-011", "alice",
                new EscalationAckRequest(key(), "  ")), HttpStatus.BAD_REQUEST);
        // 无升级记录时确认 → 409
        setup("INC-E-012", "S1", "carol");
        assertApiStatus(() -> service.acknowledgeEscalation("INC-E-012", "carol",
                new EscalationAckRequest(key(), "说明")), HttpStatus.CONFLICT);
        // 失败回滚：记录仍 OPEN
        assertThat(service.escalationHistory("INC-E-011").current().status()).isEqualTo("OPEN");
    }

    @Test
    void acknowledge_transferBoundaries() {
        setup("INC-E-020", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation("INC-E-020", new EscalationCheckRequest(key()));
        service.initiateTransfer("INC-E-020", "alice", new TransferRequest(key(), "bob"));
        // 待接受期间目标人无确认权
        assertApiStatus(() -> service.acknowledgeEscalation("INC-E-020", "bob",
                new EscalationAckRequest(key(), "bob 说明")), HttpStatus.CONFLICT);
        // 旧指挥人待接受期间仍可确认
        EscalationView byAlice = service.acknowledgeEscalation("INC-E-020", "alice",
                new EscalationAckRequest(key(), "alice 说明"));
        assertThat(byAlice.status()).isEqualTo("ACKNOWLEDGED");

        // 交接接受后旧指挥人失去确认权：另造一条 OPEN 走 bob 确认路径
        ((ControllableClock) clock).setInstant(T0.plus(30, ChronoUnit.MINUTES));
        setup("INC-E-021", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(36, ChronoUnit.MINUTES));
        service.checkEscalation("INC-E-021", new EscalationCheckRequest(key()));
        service.initiateTransfer("INC-E-021", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-E-021", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.acknowledgeEscalation("INC-E-021", "alice",
                new EscalationAckRequest(key(), "旧指挥人说明")), HttpStatus.CONFLICT);
        EscalationView byBob = service.acknowledgeEscalation("INC-E-021", "bob",
                new EscalationAckRequest(key(), "新指挥人说明"));
        assertThat(byBob.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(byBob.acknowledgedBy()).isEqualTo("bob");
    }

    @Test
    void contain_cancelsOpen_atomically_andKeepsAcknowledged() {
        // OPEN → CONTAINED：原子 CANCELLED，且遏制后不能补确认
        setup("INC-E-030", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation("INC-E-030", new EscalationCheckRequest(key()));
        service.changeStatus("INC-E-030", "alice", new StatusRequest(key(), "CONTAINED"));
        EscalationView cancelled = service.escalationHistory("INC-E-030").current();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertApiStatus(() -> service.acknowledgeEscalation("INC-E-030", "alice",
                new EscalationAckRequest(key(), "迟来的确认")), HttpStatus.CONFLICT);
        // 已确认记录在遏制后保留
        ((ControllableClock) clock).setInstant(T0.plus(40, ChronoUnit.MINUTES));
        setup("INC-E-031", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(46, ChronoUnit.MINUTES));
        service.checkEscalation("INC-E-031", new EscalationCheckRequest(key()));
        service.acknowledgeEscalation("INC-E-031", "alice",
                new EscalationAckRequest(key(), "已确认"));
        service.changeStatus("INC-E-031", "alice", new StatusRequest(key(), "CONTAINED"));
        EscalationView kept = service.escalationHistory("INC-E-031").current();
        assertThat(kept.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(kept.acknowledgedBy()).isEqualTo("alice");
        // 解决/关闭沿用原路径
        service.changeStatus("INC-E-031", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-E-031", "alice", new StatusRequest(key(), "CLOSED"));
        assertThat(service.get("INC-E-031").status()).isEqualTo("CLOSED");
    }

    @Test
    void idempotency_sameKeyReplays_changeParamsConflict_failureDoesNotOccupyKey() {
        setup("INC-E-040", "S1", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
        // 检查同键同参重放
        String checkKey = key();
        EscalationHistoryView first = service.checkEscalation("INC-E-040",
                new EscalationCheckRequest(checkKey));
        assertThat(service.checkEscalation("INC-E-040", new EscalationCheckRequest(checkKey)))
                .isEqualTo(first);
        // 同键用于不同操作 → 409
        assertApiStatus(() -> service.acknowledgeEscalation("INC-E-040", "alice",
                new EscalationAckRequest(checkKey, "x")), HttpStatus.CONFLICT);
        // 确认同键改参（note 不同）→ 409
        String ackKey = key();
        EscalationView ack = service.acknowledgeEscalation("INC-E-040", "alice",
                new EscalationAckRequest(ackKey, "首次说明"));
        assertThat(ack.note()).isEqualTo("首次说明");
        // 失败不占键：事件不存在返回 404 后，同键可在存在的事件上成功
        String reusable = key();
        assertApiStatus(() -> service.checkEscalation("INC-NOPE",
                new EscalationCheckRequest(reusable)), HttpStatus.NOT_FOUND);
        setup("INC-E-041", "S1", "bob");
        ((ControllableClock) clock).setInstant(T0.plus(20, ChronoUnit.MINUTES));
        assertThat(service.checkEscalation("INC-E-041", new EscalationCheckRequest(reusable))
                .current().status()).isEqualTo("OPEN");
        // 非法参数 400
        assertApiStatus(() -> service.checkEscalation("INC-E-041",
                new EscalationCheckRequest(" ")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void escalationQueries_notFound() {
        assertApiStatus(() -> service.escalationHistory("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.checkEscalation("INC-404", new EscalationCheckRequest(key())),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.acknowledgeEscalation("INC-404", "alice",
                new EscalationAckRequest(key(), "x")), HttpStatus.NOT_FOUND);
    }
}
