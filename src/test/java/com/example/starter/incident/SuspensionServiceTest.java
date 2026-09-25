package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResumeRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.SuspendRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.SuspensionStatusView;
import com.example.starter.incident.dto.Responses.SuspensionView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 遏制时限挂起服务测试：覆盖挂起区间从消耗中排除、剩余时限实时重算、
 * 累计上限 422、状态/权限边界、区间不可变与 commandKey 幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class SuspensionServiceTest {

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
        jdbc.update("DELETE FROM incident_suspensions");
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

    private void setup(String incidentKey, String severity, String commander) {
        service.report(new ReportRequest(incidentKey, severity, "核心链路故障", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void at(long seconds) {
        ((ControllableClock) clock).setInstant(T0.plusSeconds(seconds));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void suspend_happyPath_intervalRecorded_andRemainingFrozenWhileSuspended() {
        setup("INC-S-001", "S1", "alice");
        at(120);
        SuspensionView view = service.suspend("INC-S-001", "alice",
                new SuspendRequest(key(), "SK-1", "等待厂商远程支持"));
        assertThat(view.suspendKey()).isEqualTo("SK-1");
        assertThat(view.reason()).isEqualTo("等待厂商远程支持");
        assertThat(view.suspendedBy()).isEqualTo("alice");
        assertThat(view.suspendedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(view.resumedAt()).isNull();
        assertThat(view.resumedBy()).isNull();
        assertThat(view.resumeNote()).isNull();

        // 挂起期间剩余时限冻结：时钟推进不改变剩余值，累计挂起时长随时钟增长
        SuspensionStatusView atSuspend = service.suspensionStatus("INC-S-001");
        assertThat(atSuspend.suspended()).isTrue();
        assertThat(atSuspend.remainingSeconds()).isEqualTo(180L);
        assertThat(atSuspend.suspendedTotalSeconds()).isZero();
        assertThat(atSuspend.overdue()).isFalse();
        assertThat(atSuspend.deadlineAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(atSuspend.effectiveDeadlineAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(atSuspend.suspensions()).hasSize(1);

        at(120 + 500);
        SuspensionStatusView later = service.suspensionStatus("INC-S-001");
        assertThat(later.remainingSeconds()).isEqualTo(180L);
        assertThat(later.suspendedTotalSeconds()).isEqualTo(500L);
        assertThat(later.effectiveDeadlineAt()).isEqualTo(T0.plusSeconds(300 + 500));
        assertThat(later.overdue()).isFalse();
    }

    @Test
    void suspensionInterval_excludedFromConsumed_andDeadlineRecomputedAfterResume() {
        setup("INC-S-002", "S1", "alice");
        at(120);
        service.suspend("INC-S-002", "alice", new SuspendRequest(key(), "SK-1", "等待备件"));
        at(600);
        SuspensionView resumed = service.resume("INC-S-002", "alice",
                new ResumeRequest(key(), "SK-1", "备件到位"));
        assertThat(resumed.resumedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(resumed.resumedBy()).isEqualTo("alice");
        assertThat(resumed.resumeNote()).isEqualTo("备件到位");

        // 恢复后剩余 = 原时限 300 − 挂起前已消耗 120 = 180 秒；实际期限 = T0+780
        SuspensionStatusView status = service.suspensionStatus("INC-S-002");
        assertThat(status.suspended()).isFalse();
        assertThat(status.remainingSeconds()).isEqualTo(180L);
        assertThat(status.suspendedTotalSeconds()).isEqualTo(480L);
        assertThat(status.effectiveDeadlineAt()).isEqualTo(T0.plusSeconds(780));

        // 挂起区间整体排除：T0+779 未逾期，T0+780 触发升级且记录实际期限
        at(779);
        assertThat(service.checkEscalation("INC-S-002", new EscalationCheckRequest(key()))
                .current()).isNull();
        at(780);
        EscalationHistoryView triggered = service.checkEscalation("INC-S-002",
                new EscalationCheckRequest(key()));
        assertThat(triggered.current()).isNotNull();
        assertThat(triggered.current().status()).isEqualTo("OPEN");
        assertThat(triggered.current().deadlineAt()).isEqualTo(T0.plusSeconds(780));
    }

    @Test
    void suspendedPeriod_neverTriggersEscalation_evenPastOriginalDeadline() {
        setup("INC-S-003", "S1", "alice");
        at(60);
        service.suspend("INC-S-003", "alice", new SuspendRequest(key(), "SK-1", "等待决策"));
        // 远超原始期限（300 秒），挂起期间不触发超时升级
        at(10000);
        assertThat(service.checkEscalation("INC-S-003", new EscalationCheckRequest(key()))
                .current()).isNull();
        assertThat(service.escalationHistory("INC-S-003").current()).isNull();
        SuspensionStatusView status = service.suspensionStatus("INC-S-003");
        assertThat(status.overdue()).isFalse();
        assertThat(status.remainingSeconds()).isEqualTo(240L);
        // 恢复后按挂起前已消耗重算：再经过 240 秒才触发
        service.resume("INC-S-003", "alice", new ResumeRequest(key(), "SK-1", "决策已下达"));
        at(10000 + 240);
        assertThat(service.checkEscalation("INC-S-003", new EscalationCheckRequest(key()))
                .current().status()).isEqualTo("OPEN");
    }

    @Test
    void duplicateSuspend_conflict_andResumeBoundaries() {
        setup("INC-S-004", "S1", "alice");
        service.suspend("INC-S-004", "alice", new SuspendRequest(key(), "SK-1", "原因一"));
        // 重复挂起 409
        assertApiStatus(() -> service.suspend("INC-S-004", "alice",
                new SuspendRequest(key(), "SK-2", "原因二")), HttpStatus.CONFLICT);
        // 错误 suspendKey 恢复 409，生效挂起保持
        assertApiStatus(() -> service.resume("INC-S-004", "alice",
                new ResumeRequest(key(), "SK-OTHER", "说明")), HttpStatus.CONFLICT);
        assertThat(service.suspensionStatus("INC-S-004").suspended()).isTrue();
        // 非当前指挥人恢复 409
        assertApiStatus(() -> service.resume("INC-S-004", "bob",
                new ResumeRequest(key(), "SK-1", "说明")), HttpStatus.CONFLICT);
        // 正确恢复成功
        service.resume("INC-S-004", "alice", new ResumeRequest(key(), "SK-1", "恢复说明"));
        // 未挂起时恢复 409
        assertApiStatus(() -> service.resume("INC-S-004", "alice",
                new ResumeRequest(key(), "SK-1", "再次恢复")), HttpStatus.CONFLICT);
    }

    @Test
    void cumulativeLimit_atCap_returns422WithAccumulatedSeconds() {
        setup("INC-S-005", "S1", "alice");
        // 第一段挂起 200 秒
        at(10);
        service.suspend("INC-S-005", "alice", new SuspendRequest(key(), "SK-1", "第一段"));
        at(210);
        service.resume("INC-S-005", "alice", new ResumeRequest(key(), "SK-1", "恢复一"));
        // 已累计 200 < 上限 300，允许第二段
        service.suspend("INC-S-005", "alice", new SuspendRequest(key(), "SK-2", "第二段"));
        at(310);
        service.resume("INC-S-005", "alice", new ResumeRequest(key(), "SK-2", "恢复二"));
        // 已累计 300 = 原时限一倍，再次挂起 422 并给出已累计时长
        assertThatThrownBy(() -> service.suspend("INC-S-005", "alice",
                new SuspendRequest(key(), "SK-3", "第三段")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("SUSPENSION_LIMIT_EXCEEDED");
                    assertThat(e.getMessage()).contains("300");
                });
        // 生效中区间不计入累计上限判定：另一事件挂起 250 秒未恢复时可再次挂起校验通过
        setup("INC-S-006", "S1", "bob");
        at(320);
        service.suspend("INC-S-006", "bob", new SuspendRequest(key(), "SK-A", "首段"));
        at(570);
        service.resume("INC-S-006", "bob", new ResumeRequest(key(), "SK-A", "恢复"));
        service.suspend("INC-S-006", "bob", new SuspendRequest(key(), "SK-B", "次段"));
        assertThat(service.suspensionStatus("INC-S-006").suspended()).isTrue();
    }

    @Test
    void suspend_forbiddenOnContainedResolvedClosedAndAcknowledged() {
        // 已遏制
        setup("INC-S-010", "S1", "alice");
        service.changeStatus("INC-S-010", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> service.suspend("INC-S-010", "alice",
                new SuspendRequest(key(), "SK-1", "x")), HttpStatus.CONFLICT);
        // 已关闭
        setup("INC-S-011", "S1", "alice");
        service.changeStatus("INC-S-011", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-S-011", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-S-011", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> service.suspend("INC-S-011", "alice",
                new SuspendRequest(key(), "SK-1", "x")), HttpStatus.CONFLICT);
        // 已升级确认
        setup("INC-S-012", "S1", "alice");
        at(400);
        service.checkEscalation("INC-S-012", new EscalationCheckRequest(key()));
        service.acknowledgeEscalation("INC-S-012", "alice",
                new EscalationAckRequest(key(), "已升级处理"));
        assertApiStatus(() -> service.suspend("INC-S-012", "alice",
                new SuspendRequest(key(), "SK-1", "x")), HttpStatus.CONFLICT);
        // 未接管（REPORTED）挂起 409
        service.report(new ReportRequest("INC-S-013", "S1", "x", "r"));
        assertApiStatus(() -> service.suspend("INC-S-013", "alice",
                new SuspendRequest(key(), "SK-1", "x")), HttpStatus.CONFLICT);
        // 非当前指挥人挂起 409
        setup("INC-S-014", "S1", "alice");
        assertApiStatus(() -> service.suspend("INC-S-014", "bob",
                new SuspendRequest(key(), "SK-1", "x")), HttpStatus.CONFLICT);
    }

    @Test
    void contain_sealsActiveSuspension_atContainMoment() {
        setup("INC-S-020", "S1", "alice");
        at(60);
        service.suspend("INC-S-020", "alice", new SuspendRequest(key(), "SK-1", "等待窗口"));
        at(360);
        service.changeStatus("INC-S-020", "alice", new StatusRequest(key(), "CONTAINED"));
        SuspensionStatusView status = service.suspensionStatus("INC-S-020");
        assertThat(status.suspended()).isFalse();
        assertThat(status.suspendedTotalSeconds()).isEqualTo(300L);
        SuspensionView sealed = status.suspensions().get(0);
        assertThat(sealed.resumedAt()).isEqualTo(T0.plusSeconds(360));
        assertThat(sealed.resumedBy()).isEqualTo("alice");
        // 遏制后不能再挂起/恢复
        assertApiStatus(() -> service.suspend("INC-S-020", "alice",
                new SuspendRequest(key(), "SK-2", "x")), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.resume("INC-S-020", "alice",
                new ResumeRequest(key(), "SK-1", "x")), HttpStatus.CONFLICT);
    }

    @Test
    void intervals_immutable_historyPreservedAcrossMultipleSuspensions() {
        setup("INC-S-030", "S2", "alice");
        at(10);
        service.suspend("INC-S-030", "alice", new SuspendRequest(key(), "SK-1", "等待厂商"));
        at(70);
        service.resume("INC-S-030", "alice", new ResumeRequest(key(), "SK-1", "厂商已响应"));
        at(80);
        service.suspend("INC-S-030", "alice", new SuspendRequest(key(), "SK-2", "等待审批"));
        at(110);
        service.resume("INC-S-030", "alice", new ResumeRequest(key(), "SK-2", "审批通过"));

        SuspensionStatusView status = service.suspensionStatus("INC-S-030");
        assertThat(status.suspensions()).hasSize(2);
        SuspensionView first = status.suspensions().get(0);
        // 起始半区固化：起止时刻、操作人与原因保持首次落库值
        assertThat(first.suspendKey()).isEqualTo("SK-1");
        assertThat(first.reason()).isEqualTo("等待厂商");
        assertThat(first.suspendedBy()).isEqualTo("alice");
        assertThat(first.suspendedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(first.resumedAt()).isEqualTo(T0.plusSeconds(70));
        assertThat(first.resumeNote()).isEqualTo("厂商已响应");
        SuspensionView second = status.suspensions().get(1);
        assertThat(second.suspendedAt()).isEqualTo(T0.plusSeconds(80));
        assertThat(second.resumedAt()).isEqualTo(T0.plusSeconds(110));
        // 累计挂起 60 + 30 = 90 秒；剩余 = 900 − (110 − 90) = 880
        assertThat(status.suspendedTotalSeconds()).isEqualTo(90L);
        assertThat(status.remainingSeconds()).isEqualTo(880L);
    }

    @Test
    void idempotency_replaySameResult_changeParamsConflict_failureDoesNotOccupyKey() {
        setup("INC-S-040", "S1", "alice");
        String suspendKey = key();
        SuspensionView first = service.suspend("INC-S-040", "alice",
                new SuspendRequest(suspendKey, "SK-1", "原因"));
        // 同键同参重放首次结果
        SuspensionView replay = service.suspend("INC-S-040", "alice",
                new SuspendRequest(suspendKey, "SK-1", "原因"));
        assertThat(replay).isEqualTo(first);
        assertThat(service.suspensionStatus("INC-S-040").suspensions()).hasSize(1);
        // 同键改参 409
        assertApiStatus(() -> service.suspend("INC-S-040", "alice",
                new SuspendRequest(suspendKey, "SK-1", "另一个原因")), HttpStatus.CONFLICT);
        // 失败不占键：重复挂起 409 后，同一 commandKey 可用于后续成功操作
        String reused = key();
        assertApiStatus(() -> service.suspend("INC-S-040", "alice",
                new SuspendRequest(reused, "SK-2", "重复挂起")), HttpStatus.CONFLICT);
        SuspensionView resumed = service.resume("INC-S-040", "alice",
                new ResumeRequest(reused, "SK-1", "恢复说明"));
        assertThat(resumed.resumedAt()).isNotNull();
        // 恢复同键重放
        assertThat(service.resume("INC-S-040", "alice",
                new ResumeRequest(reused, "SK-1", "恢复说明"))).isEqualTo(resumed);
        // 参数缺失 400
        assertApiStatus(() -> service.suspend("INC-S-040", "alice",
                new SuspendRequest(key(), " ", "原因")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.resume("INC-S-040", "alice",
                new ResumeRequest(key(), "SK-1", " ")), HttpStatus.BAD_REQUEST);
        // 事件不存在 404
        assertApiStatus(() -> service.suspend("INC-NOPE", "alice",
                new SuspendRequest(key(), "SK-1", "原因")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.suspensionStatus("INC-NOPE"), HttpStatus.NOT_FOUND);
    }

    @Test
    void remainingSeconds_computedRealtime_notPersisted() {
        setup("INC-S-050", "S1", "alice");
        at(100);
        assertThat(service.suspensionStatus("INC-S-050").remainingSeconds()).isEqualTo(200L);
        at(250);
        SuspensionStatusView status = service.suspensionStatus("INC-S-050");
        assertThat(status.remainingSeconds()).isEqualTo(50L);
        assertThat(status.overdue()).isFalse();
        at(400);
        SuspensionStatusView overdue = service.suspensionStatus("INC-S-050");
        assertThat(overdue.remainingSeconds()).isZero();
        assertThat(overdue.overdue()).isTrue();
        // REPORTED 无期限：剩余为 null，不产生挂起区间
        service.report(new ReportRequest("INC-S-051", "S1", "x", "r"));
        SuspensionStatusView reported = service.suspensionStatus("INC-S-051");
        assertThat(reported.deadlineAt()).isNull();
        assertThat(reported.remainingSeconds()).isNull();
        assertThat(reported.suspended()).isFalse();
        assertThat(reported.suspensions()).isEmpty();
    }
}
