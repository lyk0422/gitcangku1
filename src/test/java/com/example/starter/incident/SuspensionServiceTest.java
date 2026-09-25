package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResumeRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.SuspendRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.SuspensionHistoryView;
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
 * 遏制时限挂起服务测试（真实 H2 MySQL 兼容库）：覆盖挂起区间排除、挂起期间冻结
 * 不触发升级、恢复后剩余时限重算、重复挂起/恢复/错键/状态/权限冲突、累计上限 422、
 * 区间不可变、commandKey 同键重放/改参 409/失败不占键。时间由可控 Clock 驱动。
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

    private IncidentView setup(String incidentKey, String severity, String commander) {
        service.report(new ReportRequest(incidentKey, severity, "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void advance(String iso) {
        ((ControllableClock) clock).setInstant(Instant.parse(iso));
    }

    private void advanceSeconds(long seconds) {
        ((ControllableClock) clock).advanceSeconds(seconds);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void suspend_freezesRemaining_resumeRecomputes_intervalRecordedImmutably() {
        setup("INC-S-001", "S1", "alice");
        // T0+2m 挂起：挂起前已消耗 2 分钟，剩 3 分钟
        advance("2026-09-22T00:02:00Z");
        SuspensionView suspended = service.suspend("INC-S-001", "alice",
                new SuspendRequest(key(), "SK-1", "等待第三方厂商到场"));
        assertThat(suspended.id()).isNotNull();
        assertThat(suspended.suspendKey()).isEqualTo("SK-1");
        assertThat(suspended.reason()).isEqualTo("等待第三方厂商到场");
        assertThat(suspended.suspendedBy()).isEqualTo("alice");
        assertThat(suspended.suspendedAt()).isEqualTo(T0.plus(2, ChronoUnit.MINUTES));
        assertThat(suspended.resumedAt()).isNull();
        assertThat(suspended.resumedBy()).isNull();
        assertThat(suspended.resumeNote()).isNull();
        assertThat(suspended.suspendedDurationMillis()).isZero();

        SuspensionHistoryView during = service.suspensionHistory("INC-S-001");
        assertThat(during.suspended()).isTrue();
        assertThat(during.originalDeadlineAt()).isEqualTo(T0.plus(5, ChronoUnit.MINUTES));
        assertThat(during.capMillis()).isEqualTo(300_000L);
        assertThat(during.remainingMillis()).isEqualTo(180_000L);
        assertThat(during.totalSuspendedMillis()).isZero();

        // 墙钟流过 28 分钟，挂起期间剩余冻结不递减，有效期限随当前时刻顺延
        advance("2026-09-22T00:30:00Z");
        SuspensionHistoryView frozen = service.suspensionHistory("INC-S-001");
        assertThat(frozen.remainingMillis()).isEqualTo(180_000L);
        assertThat(frozen.totalSuspendedMillis()).isEqualTo(28 * 60_000L);
        assertThat(frozen.effectiveDeadlineAt()).isEqualTo(T0.plus(33, ChronoUnit.MINUTES));

        // T0+30m 恢复：区间 28 分钟整体封口排除，恢复瞬间剩 3 分钟
        SuspensionView resumed = service.resume("INC-S-001", "alice",
                new ResumeRequest(key(), "SK-1", "厂商已到场，继续处置"));
        assertThat(resumed.id()).isEqualTo(suspended.id());
        assertThat(resumed.resumedAt()).isEqualTo(T0.plus(30, ChronoUnit.MINUTES));
        assertThat(resumed.resumedBy()).isEqualTo("alice");
        assertThat(resumed.resumeNote()).isEqualTo("厂商已到场，继续处置");
        assertThat(resumed.suspendedDurationMillis()).isEqualTo(28 * 60_000L);

        SuspensionHistoryView after = service.suspensionHistory("INC-S-001");
        assertThat(after.suspended()).isFalse();
        assertThat(after.remainingMillis()).isEqualTo(180_000L);
        assertThat(after.totalSuspendedMillis()).isEqualTo(28 * 60_000L);
        // 恢复后有效期限固定顺延 28 分钟，不再随墙钟变化
        assertThat(after.effectiveDeadlineAt()).isEqualTo(T0.plus(33, ChronoUnit.MINUTES));
        advance("2026-09-22T00:40:00Z");
        assertThat(service.suspensionHistory("INC-S-001").effectiveDeadlineAt())
                .isEqualTo(T0.plus(33, ChronoUnit.MINUTES));
        // 恢复后继续活跃消耗 10 分钟：剩 3-10 为负 7 分钟（已超时）
        assertThat(service.suspensionHistory("INC-S-001").remainingMillis())
                .isEqualTo(-7 * 60_000L);

        // 区间历史固化：S3 事件两段挂起后，第一条区间起止时刻与原因不被改写
        setup("INC-S-001B", "S3", "alice");
        advance("2026-09-22T01:02:00Z");
        service.suspend("INC-S-001B", "alice",
                new SuspendRequest(key(), "SK-1", "等待第三方厂商到场"));
        advance("2026-09-22T01:30:00Z");
        service.resume("INC-S-001B", "alice", new ResumeRequest(key(), "SK-1", "厂商到场"));
        advance("2026-09-22T01:31:00Z");
        service.suspend("INC-S-001B", "alice", new SuspendRequest(key(), "SK-2", "二次挂起"));
        advance("2026-09-22T01:32:00Z");
        service.resume("INC-S-001B", "alice", new ResumeRequest(key(), "SK-2", "二次恢复"));
        List<SuspensionView> intervals = service.suspensionHistory("INC-S-001B").intervals();
        assertThat(intervals).hasSize(2);
        assertThat(intervals.get(0).suspendedAt()).isEqualTo(Instant.parse("2026-09-22T01:02:00Z"));
        assertThat(intervals.get(0).resumedAt()).isEqualTo(Instant.parse("2026-09-22T01:30:00Z"));
        assertThat(intervals.get(0).reason()).isEqualTo("等待第三方厂商到场");
        assertThat(intervals.get(0).suspendedDurationMillis()).isEqualTo(28 * 60_000L);
        assertThat(intervals.get(1).suspendKey()).isEqualTo("SK-2");
        assertThat(intervals.get(1).suspendedDurationMillis()).isEqualTo(60_000L);
    }

    @Test
    void repeatedSuspend_conflict409_resumeWithoutSuspensionOrWrongKey_conflict409() {
        setup("INC-S-002", "S1", "alice");
        advanceSeconds(60);
        service.suspend("INC-S-002", "alice", new SuspendRequest(key(), "SK-A", "原因A"));
        // 重复挂起 409
        assertApiStatus(() -> service.suspend("INC-S-002", "alice",
                new SuspendRequest(key(), "SK-B", "原因B")), HttpStatus.CONFLICT);
        // 错 suspendKey 恢复 409，且区间仍生效
        assertApiStatus(() -> service.resume("INC-S-002", "alice",
                new ResumeRequest(key(), "SK-WRONG", "说明")), HttpStatus.CONFLICT);
        assertThat(service.suspensionHistory("INC-S-002").suspended()).isTrue();
        // 非指挥人挂起/恢复 409
        assertApiStatus(() -> service.suspend("INC-S-002", "bob",
                new SuspendRequest(key(), "SK-C", "x")), HttpStatus.CONFLICT);
        // 正确键恢复成功
        service.resume("INC-S-002", "alice", new ResumeRequest(key(), "SK-A", "恢复"));
        assertThat(service.suspensionHistory("INC-S-002").suspended()).isFalse();
        // 未挂起时恢复 409
        assertApiStatus(() -> service.resume("INC-S-002", "alice",
                new ResumeRequest(key(), "SK-A", "再次恢复")), HttpStatus.CONFLICT);
    }

    @Test
    void suspend_onContainedResolvedClosedOrAcknowledged_conflict409() {
        setup("INC-S-003", "S1", "alice");
        service.changeStatus("INC-S-003", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> service.suspend("INC-S-003", "alice",
                new SuspendRequest(key(), "SK", "r")), HttpStatus.CONFLICT);
        service.changeStatus("INC-S-003", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> service.suspend("INC-S-003", "alice",
                new SuspendRequest(key(), "SK", "r")), HttpStatus.CONFLICT);
        service.changeStatus("INC-S-003", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> service.suspend("INC-S-003", "alice",
                new SuspendRequest(key(), "SK", "r")), HttpStatus.CONFLICT);

        // 已升级确认不得挂起
        setup("INC-S-004", "S1", "alice");
        advance("2026-09-22T00:06:00Z");
        service.checkEscalation("INC-S-004", new EscalationCheckRequest(key()));
        service.acknowledgeEscalation("INC-S-004", "alice",
                new com.example.starter.incident.dto.Requests.EscalationAckRequest(key(), "已确认"));
        assertApiStatus(() -> service.suspend("INC-S-004", "alice",
                new SuspendRequest(key(), "SK", "r")), HttpStatus.CONFLICT);

        // REPORTED 未接管不得挂起（无指挥人）
        service.report(new ReportRequest("INC-S-005", "S1", "x", "r"));
        assertApiStatus(() -> service.suspend("INC-S-005", "alice",
                new SuspendRequest(key(), "SK", "r")), HttpStatus.CONFLICT);
    }

    @Test
    void cumulativeSuspensionCap_oneTimesLimit_returns422WithAccumulated() {
        setup("INC-S-006", "S1", "alice"); // 上限 5 分钟
        // 第一段 2 分钟：[1m,3m]
        advance("2026-09-22T00:01:00Z");
        service.suspend("INC-S-006", "alice", new SuspendRequest(key(), "K1", "r"));
        advance("2026-09-22T00:03:00Z");
        service.resume("INC-S-006", "alice", new ResumeRequest(key(), "K1", "n"));
        // 第二段 2 分钟：[4m,6m]，累计 4 分钟，仍可挂起
        advance("2026-09-22T00:04:00Z");
        service.suspend("INC-S-006", "alice", new SuspendRequest(key(), "K2", "r"));
        advance("2026-09-22T00:06:00Z");
        service.resume("INC-S-006", "alice", new ResumeRequest(key(), "K2", "n"));
        // 第三段 3 分钟：[7m,10m]，累计 7 分钟（>= 上限 5 分钟）
        advance("2026-09-22T00:07:00Z");
        service.suspend("INC-S-006", "alice", new SuspendRequest(key(), "K3", "r"));
        advance("2026-09-22T00:10:00Z");
        service.resume("INC-S-006", "alice", new ResumeRequest(key(), "K3", "n"));
        // 再次挂起 422，消息给出已累计 420000ms 与上限 300000ms
        assertThatThrownBy(() -> service.suspend("INC-S-006", "alice",
                new SuspendRequest(key(), "K4", "r")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).contains("420000").contains("300000");
                });
        // 422 失败不产生区间
        assertThat(service.suspensionHistory("INC-S-006").intervals()).hasSize(3);
        assertThat(service.suspensionHistory("INC-S-006").suspended()).isFalse();
    }

    @Test
    void suspensionBlocksOverdueEscalation_resumeDefersDeadline() {
        setup("INC-S-007", "S1", "alice");
        advance("2026-09-22T00:04:00Z");
        service.suspend("INC-S-007", "alice", new SuspendRequest(key(), "SK", "等待外部"));
        // 墙钟远超原始 5 分钟期限，但挂起期间检查不触发升级
        advance("2026-09-22T00:30:00Z");
        EscalationHistoryView during = service.checkEscalation("INC-S-007",
                new EscalationCheckRequest(key()));
        assertThat(during.current()).isNull();
        // T0+30m 恢复：顺延 26 分钟，有效期限 T0+31m，恢复瞬间剩 1 分钟
        service.resume("INC-S-007", "alice", new ResumeRequest(key(), "SK", "继续"));
        assertThat(service.suspensionHistory("INC-S-007").effectiveDeadlineAt())
                .isEqualTo(T0.plus(31, ChronoUnit.MINUTES));
        // T0+30m30s 仍未到期
        advance("2026-09-22T00:30:30Z");
        assertThat(service.checkEscalation("INC-S-007", new EscalationCheckRequest(key()))
                .current()).isNull();
        // T0+31m 到达顺延后的有效期限，检查触发升级，记录保存原始 deadlineAt
        advance("2026-09-22T00:31:00Z");
        EscalationHistoryView overdue = service.checkEscalation("INC-S-007",
                new EscalationCheckRequest(key()));
        assertThat(overdue.current()).isNotNull();
        assertThat(overdue.current().status()).isEqualTo("OPEN");
        assertThat(overdue.current().deadlineAt()).isEqualTo(T0.plus(5, ChronoUnit.MINUTES));
    }

    @Test
    void commandIdempotency_replay_sameKeyDifferentParamsConflict_failureFreesKey() {
        setup("INC-S-008", "S1", "alice");
        advanceSeconds(60);
        // 挂起同键同参重放：返回同一区间，仅一条记录
        String suspendCmd = key();
        SuspensionView first = service.suspend("INC-S-008", "alice",
                new SuspendRequest(suspendCmd, "SK", "首次原因"));
        SuspensionView replay = service.suspend("INC-S-008", "alice",
                new SuspendRequest(suspendCmd, "SK", "首次原因"));
        assertThat(replay).isEqualTo(first);
        assertThat(service.suspensionHistory("INC-S-008").intervals()).hasSize(1);
        // 同键改参（原因不同）409
        assertApiStatus(() -> service.suspend("INC-S-008", "alice",
                new SuspendRequest(suspendCmd, "SK", "改过的原因")), HttpStatus.CONFLICT);
        // 同键改 suspendKey 409
        assertApiStatus(() -> service.suspend("INC-S-008", "alice",
                new SuspendRequest(suspendCmd, "SK-OTHER", "首次原因")), HttpStatus.CONFLICT);
        // 同键用于恢复操作 409
        assertApiStatus(() -> service.resume("INC-S-008", "alice",
                new ResumeRequest(suspendCmd, "SK", "x")), HttpStatus.CONFLICT);

        // 恢复同键同参重放
        String resumeCmd = key();
        advanceSeconds(120);
        SuspensionView resumed = service.resume("INC-S-008", "alice",
                new ResumeRequest(resumeCmd, "SK", "恢复说明"));
        assertThat(service.resume("INC-S-008", "alice",
                new ResumeRequest(resumeCmd, "SK", "恢复说明"))).isEqualTo(resumed);

        // 失败不占键：错键恢复 409 后，同一 commandKey 可用于正确的……此处已无挂起，
        // 改为先挂起，验证失败键可复用
        String reusable = key();
        advanceSeconds(60);
        service.suspend("INC-S-008", "alice", new SuspendRequest(key(), "SK2", "r2"));
        assertApiStatus(() -> service.resume("INC-S-008", "alice",
                new ResumeRequest(reusable, "WRONG", "x")), HttpStatus.CONFLICT);
        SuspensionView resumed2 = service.resume("INC-S-008", "alice",
                new ResumeRequest(reusable, "SK2", "正确恢复"));
        assertThat(resumed2.suspendKey()).isEqualTo("SK2");
    }

    @Test
    void blankFields_badRequest_andReportedQueryEmptyDeadline() {
        setup("INC-S-009", "S1", "alice");
        assertApiStatus(() -> service.suspend("INC-S-009", "alice",
                new SuspendRequest(" ", "SK", "r")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.suspend("INC-S-009", "alice",
                new SuspendRequest(key(), " ", "r")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.suspend("INC-S-009", "alice",
                new SuspendRequest(key(), "SK", "  ")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.resume("INC-S-009", "alice",
                new ResumeRequest(key(), "SK", " ")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.suspensionHistory("INC-404"), HttpStatus.NOT_FOUND);

        // REPORTED 事件查询：无期限、无区间
        service.report(new ReportRequest("INC-S-009-R", "S1", "x", "r"));
        SuspensionHistoryView view = service.suspensionHistory("INC-S-009-R");
        assertThat(view.originalDeadlineAt()).isNull();
        assertThat(view.effectiveDeadlineAt()).isNull();
        assertThat(view.suspended()).isFalse();
        assertThat(view.intervals()).isEmpty();
        assertThat(view.remainingMillis()).isZero();
    }
}
