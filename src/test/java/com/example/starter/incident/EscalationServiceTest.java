package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.EscalationCheckView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * “逾期未遏制”升级服务测试：使用可控 Clock 在真实 H2（MODE=MySQL）上覆盖
 * 期限计算、检查/确认/取消主流程、失败分支、幂等与交接确认权边界。
 */
@SpringBootTest
@Import(EscalationServiceTest.TestClockConfig.class)
class EscalationServiceTest {

    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");

    @BeforeEach
    void clean() {
        clock.reset();
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    /** 在 T0 上报指定等级事件，alice 于 T0 接管。 */
    private IncidentView start(String incidentKey, String severity) {
        clock.set(T0);
        service.report(new ReportRequest(incidentKey, severity, "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
    }

    @Test
    void deadlineBySeverity_andTransferDoesNotReset() {
        assertThat(start("INC-S1", "S1").containmentDeadline()).isEqualTo(T0.plus(5, ChronoUnit.MINUTES));
        assertThat(start("INC-S2", "S2").containmentDeadline()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));
        assertThat(start("INC-S3", "S3").containmentDeadline()).isEqualTo(T0.plus(60, ChronoUnit.MINUTES));
        assertThat(start("INC-S4", "S4").containmentDeadline()).isEqualTo(T0.plus(240, ChronoUnit.MINUTES));

        // S2 事件：交接在 T0+100min 接受后，期限仍为接管时的 T0+15min
        String ik = "INC-S2";
        clock.set(T0.plus(100, ChronoUnit.MINUTES));
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer(ik, "bob", new TransferAcceptRequest(key()));
        assertThat(service.get(ik).containmentDeadline()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));
        assertThat(service.get(ik).commander()).isEqualTo("bob");
    }

    @Test
    void check_beforeDeadline_createsNothing_andQueryDoesNotWrite() {
        String ik = "INC-C01";
        start(ik, "S2");
        clock.set(T0.plus(14, ChronoUnit.MINUTES).plusSeconds(59));
        EscalationCheckView result = service.checkEscalation(ik, new EscalationCheckRequest(key()));
        assertThat(result.created()).isFalse();
        assertThat(result.escalation()).isNull();
        assertThat(result.deadline()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));

        // 只读查询不隐式写入
        EscalationHistoryView view = service.escalations(ik);
        assertThat(view.current()).isNull();
        assertThat(view.history()).isEmpty();
        assertThat(view.deadline()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));
        assertThat(service.history(ik).escalations()).isEmpty();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_escalations WHERE incident_id = "
                        + "(SELECT id FROM incidents WHERE incident_key = '" + ik + "')", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void check_exactlyAtDeadline_triggers() {
        String ik = "INC-C01B";
        start(ik, "S2");
        // 当前时刻恰好等于期限（≥），触发
        clock.set(T0.plus(15, ChronoUnit.MINUTES));
        EscalationCheckView result = service.checkEscalation(ik, new EscalationCheckRequest(key()));
        assertThat(result.created()).isTrue();
        assertThat(result.escalation().status()).isEqualTo("OPEN");
    }

    @Test
    void check_afterDeadline_createsOpenOnce() {
        String ik = "INC-C02";
        start(ik, "S1");
        Instant trigger = T0.plus(5, ChronoUnit.MINUTES);
        clock.set(trigger);

        EscalationCheckView first = service.checkEscalation(ik, new EscalationCheckRequest(key()));
        assertThat(first.created()).isTrue();
        EscalationView e = first.escalation();
        assertThat(e.status()).isEqualTo("OPEN");
        assertThat(e.deadline()).isEqualTo(T0.plus(5, ChronoUnit.MINUTES));
        assertThat(e.triggeredAt()).isEqualTo(trigger);
        assertThat(e.commander()).isEqualTo("alice");
        assertThat(e.dispositionNote()).isNull();
        assertThat(e.acknowledgedBy()).isNull();
        assertThat(e.acknowledgedAt()).isNull();

        // 再次检查（换键）不新增
        clock.set(T0.plus(20, ChronoUnit.MINUTES));
        EscalationCheckView again = service.checkEscalation(ik, new EscalationCheckRequest(key()));
        assertThat(again.created()).isFalse();
        assertThat(again.escalation().id()).isEqualTo(e.id());
        assertThat(again.escalation().status()).isEqualTo("OPEN");
        assertThat(service.escalations(ik).history()).hasSize(1);
    }

    @Test
    void check_otherStates_createNothing() {
        // REPORTED：尚未接管，无期限，检查不产生记录
        clock.set(T0);
        service.report(new ReportRequest("INC-C03", "S1", "x", "r"));
        clock.set(T0.plus(30, ChronoUnit.MINUTES));
        EscalationCheckView reported = service.checkEscalation("INC-C03", new EscalationCheckRequest(key()));
        assertThat(reported.created()).isFalse();
        assertThat(reported.deadline()).isNull();

        // CONTAINED / RESOLVED / CLOSED 检查均不产生记录
        String ik = "INC-C04";
        start(ik, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.checkEscalation(ik, new EscalationCheckRequest(key())).created()).isFalse();
        service.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
        assertThat(service.checkEscalation(ik, new EscalationCheckRequest(key())).created()).isFalse();
        service.changeStatus(ik, "alice", new StatusRequest(key(), "CLOSED"));
        assertThat(service.checkEscalation(ik, new EscalationCheckRequest(key())).created()).isFalse();
        assertThat(service.escalations(ik).history()).isEmpty();
    }

    @Test
    void acknowledge_openByCurrentCommander() {
        String ik = "INC-C05";
        start(ik, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(ik, new EscalationCheckRequest(key()));

        Instant ackTime = T0.plus(7, ChronoUnit.MINUTES);
        clock.set(ackTime);
        EscalationView ack = service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(key(), "已扩容并限流，正在恢复"));
        assertThat(ack.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.acknowledgedBy()).isEqualTo("alice");
        assertThat(ack.acknowledgedAt()).isEqualTo(ackTime);
        assertThat(ack.dispositionNote()).isEqualTo("已扩容并限流，正在恢复");

        EscalationHistoryView view = service.escalations(ik);
        assertThat(view.current().status()).isEqualTo("ACKNOWLEDGED");
        assertThat(view.history()).hasSize(1);
    }

    @Test
    void acknowledge_nonCommanderAndPendingTarget_forbidden() {
        String ik = "INC-C06";
        start(ik, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(ik, new EscalationCheckRequest(key()));

        // 非当前指挥人无权确认
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "bob",
                new EscalationAckRequest(key(), "说明")), HttpStatus.CONFLICT);
        // 处置说明为空 400
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(key(), "  ")), HttpStatus.BAD_REQUEST);

        // 待接受期间：目标人无确认权，旧指挥人仍可确认
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "bob",
                new EscalationAckRequest(key(), "目标人尝试确认")), HttpStatus.CONFLICT);
        EscalationView byOld = service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(key(), "交接期间旧指挥人确认"));
        assertThat(byOld.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(byOld.acknowledgedBy()).isEqualTo("alice");

        // 接受后旧指挥人失去确认权（记录已确认，再次确认同样 409）
        service.acceptTransfer(ik, "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(key(), "旧指挥人再确认")), HttpStatus.CONFLICT);
    }

    @Test
    void acknowledge_afterTransfer_newCommanderConfirms() {
        String ik = "INC-C07";
        start(ik, "S2");
        // T0+20 先交接（此时已逾期但未检查），接受后再检查，触发当时指挥人为 bob
        clock.set(T0.plus(20, ChronoUnit.MINUTES));
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer(ik, "bob", new TransferAcceptRequest(key()));
        EscalationCheckView check = service.checkEscalation(ik, new EscalationCheckRequest(key()));
        assertThat(check.created()).isTrue();
        assertThat(check.escalation().commander()).isEqualTo("bob");

        // alice 已失去确认权，bob 可确认
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(key(), "旧指挥人")), HttpStatus.CONFLICT);
        EscalationView ack = service.acknowledgeEscalation(ik, "bob",
                new EscalationAckRequest(key(), "新指挥人确认"));
        assertThat(ack.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.acknowledgedBy()).isEqualTo("bob");
    }

    @Test
    void acknowledge_withoutOpenRecord_conflict() {
        String ik = "INC-C08";
        start(ik, "S1");
        // 期限前无记录
        clock.set(T0.plus(1, ChronoUnit.MINUTES));
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(key(), "说明")), HttpStatus.CONFLICT);
    }

    @Test
    void contain_cancelsOpen_preservesAcknowledged() {
        // OPEN → CONTAINED：原子 CANCELLED
        String openIk = "INC-C09";
        start(openIk, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(openIk, new EscalationCheckRequest(key()));
        service.changeStatus(openIk, "alice", new StatusRequest(key(), "CONTAINED"));
        EscalationView cancelled = service.escalations(openIk).current();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        // 取消后不能补确认
        assertApiStatus(() -> service.acknowledgeEscalation(openIk, "alice",
                new EscalationAckRequest(key(), "补确认")), HttpStatus.CONFLICT);

        // ACKNOWLEDGED → CONTAINED：记录保留
        String ackIk = "INC-C10";
        start(ackIk, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(ackIk, new EscalationCheckRequest(key()));
        service.acknowledgeEscalation(ackIk, "alice", new EscalationAckRequest(key(), "已处置"));
        service.changeStatus(ackIk, "alice", new StatusRequest(key(), "CONTAINED"));
        EscalationView kept = service.escalations(ackIk).current();
        assertThat(kept.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(kept.acknowledgedBy()).isEqualTo("alice");
    }

    @Test
    void cannotRollbackOrLateAckAfterClosed() {
        String ik = "INC-C11";
        start(ik, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(ik, new EscalationCheckRequest(key()));
        service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus(ik, "alice", new StatusRequest(key(), "CLOSED"));
        // 关闭后补确认：状态变更被 422 拦截在更早路径前；确认入口本身报 409（记录已 CANCELLED）
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(key(), "关闭后补确认")), HttpStatus.CONFLICT);
        // 记录仍为 CANCELLED，未回退
        assertThat(service.escalations(ik).current().status()).isEqualTo("CANCELLED");
    }

    @Test
    void checkAndAck_idempotentReplay() {
        String ik = "INC-C12";
        start(ik, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        String checkKey = key();
        EscalationCheckView first = service.checkEscalation(ik, new EscalationCheckRequest(checkKey));
        EscalationCheckView replay = service.checkEscalation(ik, new EscalationCheckRequest(checkKey));
        assertThat(replay).isEqualTo(first);

        String ackKey = key();
        EscalationView ackFirst = service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(ackKey, "首次说明"));
        EscalationView ackReplay = service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(ackKey, "首次说明"));
        assertThat(ackReplay).isEqualTo(ackFirst);
        // 重放不产生第二条记录、不改变确认时间
        assertThat(service.escalations(ik).history()).hasSize(1);
    }

    @Test
    void check_sameKeyDifferentParams_conflict() {
        start("INC-C13", "S1");
        start("INC-C14", "S1");
        String commandKey = key();
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation("INC-C13", new EscalationCheckRequest(commandKey));
        // 同键用于另一事件（参数不同）→ 409
        assertApiStatus(() -> service.checkEscalation("INC-C14", new EscalationCheckRequest(commandKey)),
                HttpStatus.CONFLICT);
        // 同键用于不同操作 → 409
        assertApiStatus(() -> service.acknowledgeEscalation("INC-C13", "alice",
                new EscalationAckRequest(commandKey, "x")), HttpStatus.CONFLICT);
    }

    @Test
    void ack_sameKeyDifferentNote_conflict() {
        String ik = "INC-C15";
        start(ik, "S1");
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(ik, new EscalationCheckRequest(key()));
        String commandKey = key();
        service.acknowledgeEscalation(ik, "alice", new EscalationAckRequest(commandKey, "说明A"));
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(commandKey, "说明B")), HttpStatus.CONFLICT);
    }

    @Test
    void failure_doesNotOccupyKey() {
        String ik = "INC-C16";
        start(ik, "S1");
        // 期限前确认（无 OPEN 记录）→ 409，键不被占用
        clock.set(T0.plus(1, ChronoUnit.MINUTES));
        String commandKey = key();
        assertApiStatus(() -> service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(commandKey, "说明")), HttpStatus.CONFLICT);
        Integer occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(occupied).isZero();

        // 逾期后产生 OPEN，同一键可成功用于确认（失败未占键）
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        service.checkEscalation(ik, new EscalationCheckRequest(key()));
        EscalationView ack = service.acknowledgeEscalation(ik, "alice",
                new EscalationAckRequest(commandKey, "说明"));
        assertThat(ack.status()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void beforeDeadlineSuccess_replayNotReevaluated_newKeyRequired() {
        String ik = "INC-C17";
        start(ik, "S1");
        // 期限前成功检查并占用 commandKey
        clock.set(T0.plus(1, ChronoUnit.MINUTES));
        String commandKey = key();
        EscalationCheckView early = service.checkEscalation(ik, new EscalationCheckRequest(commandKey));
        assertThat(early.created()).isFalse();

        // 时钟越过期限后用同键重放：返回首次结果，不重新评估、不产生记录
        clock.set(T0.plus(10, ChronoUnit.MINUTES));
        EscalationCheckView replay = service.checkEscalation(ik, new EscalationCheckRequest(commandKey));
        assertThat(replay).isEqualTo(early);
        assertThat(service.escalations(ik).history()).isEmpty();

        // 换键重新检查才评估并新增 OPEN
        EscalationCheckView fresh = service.checkEscalation(ik, new EscalationCheckRequest(key()));
        assertThat(fresh.created()).isTrue();
        assertThat(fresh.escalation().status()).isEqualTo("OPEN");
    }

    @Test
    void check_invalidParamsAndNotFound() {
        assertApiStatus(() -> service.checkEscalation("INC-404", new EscalationCheckRequest(key())),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.acknowledgeEscalation("INC-404", "alice",
                new EscalationAckRequest(key(), "x")), HttpStatus.NOT_FOUND);
        start("INC-C18", "S1");
        assertApiStatus(() -> service.checkEscalation("INC-C18", new EscalationCheckRequest("  ")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.escalations("INC-404"), HttpStatus.NOT_FOUND);
    }
}
