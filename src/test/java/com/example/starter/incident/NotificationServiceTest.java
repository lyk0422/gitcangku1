package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.NotificationAckRequest;
import com.example.starter.incident.dto.Requests.NotifyAgencyRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.AgencyAckView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.NotificationView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部通报回执服务测试：覆盖通报随升级创建、机构稳定排序、首份有效回执语义、
 * requestId 重放/改参 409/失败不占键、关键机构逾期降级门禁（含恰好到期）、
 * 普通机构逾期仅待跟进、补回执不改写升级与降级记录等状态边界。
 * 全部基于真实 H2（MODE=MySQL）内存库，时间由可控 Clock 驱动，不依赖睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class NotificationServiceTest {

    /** 接管时刻：S1 遏制期限为 11:05Z。 */
    private static final Instant T0 = Instant.parse("2026-09-29T11:00:00Z");
    /** 升级检查时刻（已过遏制期限），通报在此刻创建。 */
    private static final Instant TRIGGER = Instant.parse("2026-09-29T11:06:00Z");
    /** 题干约定的机构 UTC 回执截止时刻 12:00Z。 */
    private static final Instant ACK_DEADLINE = Instant.parse("2026-09-29T12:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_notification_acks");
        jdbc.update("DELETE FROM incident_notification_agencies");
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM command_keys");
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

    /**
     * 构造题干事件 E：S1 事件 11:00 由 alice 接管，11:06 检查触发升级并创建通报，
     * 关键机构 jia 与普通机构 yi 均需在 12:00Z 前回执。
     */
    private void setupEventE(String incidentKey) {
        ((ControllableClock) clock).setInstant(T0);
        service.report(new ReportRequest(incidentKey, "S1", "事件E", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(TRIGGER);
        // 故意乱序传入，验证查询按机构键稳定排序
        EscalationHistoryView view = service.checkEscalation(incidentKey,
                new EscalationCheckRequest(key(), List.of(
                        new NotifyAgencyRequest("yi", false, ACK_DEADLINE),
                        new NotifyAgencyRequest("jia", true, ACK_DEADLINE))));
        assertThat(view.current()).isNotNull();
        assertThat(view.current().status()).isEqualTo("OPEN");
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void notification_createdOnEscalation_agenciesSorted_noneOverdueBeforeDeadline() {
        setupEventE("INC-N-001");
        NotificationView view = service.notificationStatus("INC-N-001");
        assertThat(view.totalCount()).isEqualTo(2);
        assertThat(view.acknowledgedCount()).isZero();
        assertThat(view.agencies()).extracting(AgencyAckView::agencyKey)
                .containsExactly("jia", "yi");
        AgencyAckView jia = view.agencies().get(0);
        assertThat(jia.critical()).isTrue();
        assertThat(jia.acknowledged()).isFalse();
        assertThat(jia.overdue()).isFalse();
        assertThat(jia.firstRequestId()).isNull();
        assertThat(jia.signer()).isNull();
        assertThat(jia.receivedAt()).isNull();
        assertThat(view.overdueCritical()).isEmpty();
        assertThat(view.followUp()).isEmpty();
        assertThat(view.escalationId()).isNotNull();
    }

    @Test
    void deadline_exactlyNow_isOverdue_andCriticalMissingListed() {
        setupEventE("INC-N-002");
        // 截止前一刻：不逾期
        ((ControllableClock) clock).setInstant(ACK_DEADLINE.minusSeconds(1));
        assertThat(service.notificationStatus("INC-N-002").overdueCritical()).isEmpty();
        assertThat(service.notificationStatus("INC-N-002").followUp()).isEmpty();
        // 恰好到期：两机构均未回执，关键机构进缺失清单，普通机构进待跟进
        ((ControllableClock) clock).setInstant(ACK_DEADLINE);
        NotificationView atDeadline = service.notificationStatus("INC-N-002");
        assertThat(atDeadline.overdueCritical()).containsExactly("jia");
        assertThat(atDeadline.followUp()).containsExactly("yi");
        assertThat(atDeadline.agencies()).allSatisfy(a -> {
            assertThat(a.acknowledged()).isFalse();
            assertThat(a.overdue()).isTrue();
        });
    }

    @Test
    void firstAckOnly_countsOnce_laterDuplicatesDoNotOverwrite() {
        setupEventE("INC-N-003");
        Instant firstAt = Instant.parse("2026-09-29T11:30:00Z");
        ((ControllableClock) clock).setInstant(firstAt);
        String firstRequestId = key();
        NotificationAckResult first = service.recordNotificationAck("INC-N-003",
                new NotificationAckRequest(firstRequestId, "jia", "zhang", "已知悉"));
        assertThat(first.firstAck()).isTrue();
        assertThat(first.view().acknowledgedCount()).isEqualTo(1);

        // 同机构后来的重复报文（新 requestId、不同签收人/内容）：不增加完成数、不覆盖首份
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:45:00Z"));
        NotificationAckResult dup = service.recordNotificationAck("INC-N-003",
                new NotificationAckRequest(key(), "jia", "li", "重复报文"));
        assertThat(dup.firstAck()).isFalse();
        assertThat(dup.view().acknowledgedCount()).isEqualTo(1);
        AgencyAckView jia = dup.view().agencies().stream()
                .filter(a -> a.agencyKey().equals("jia")).findFirst().orElseThrow();
        assertThat(jia.acknowledged()).isTrue();
        assertThat(jia.firstRequestId()).isEqualTo(firstRequestId);
        assertThat(jia.signer()).isEqualTo("zhang");
        assertThat(jia.receivedAt()).isEqualTo(firstAt);
        // 原始报文表留存两份报文
        Integer rawRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_acks", Integer.class);
        assertThat(rawRows).isEqualTo(2);
        Integer agencyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_agencies", Integer.class);
        assertThat(agencyRows).isEqualTo(2);
    }

    @Test
    void sameRequestId_replaysOriginal_changedAgencyOrContent_conflict() {
        setupEventE("INC-N-004");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:30:00Z"));
        String requestId = key();
        NotificationAckResult first = service.recordNotificationAck("INC-N-004",
                new NotificationAckRequest(requestId, "jia", "zhang", "已知悉"));
        assertThat(first.firstAck()).isTrue();
        // 同 requestId 同机构同内容重放：首次结果（firstAck 仍为 true），不新增完成数
        NotificationAckResult replay = service.recordNotificationAck("INC-N-004",
                new NotificationAckRequest(requestId, "jia", "zhang", "已知悉"));
        assertThat(replay).isEqualTo(first);
        Integer rawRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_acks", Integer.class);
        assertThat(rawRows).isEqualTo(1);
        // 换机构 → 409
        assertApiStatus(() -> service.recordNotificationAck("INC-N-004",
                new NotificationAckRequest(requestId, "yi", "zhang", "已知悉")), HttpStatus.CONFLICT);
        // 换签收人 → 409
        assertApiStatus(() -> service.recordNotificationAck("INC-N-004",
                new NotificationAckRequest(requestId, "jia", "li", "已知悉")), HttpStatus.CONFLICT);
        // 换内容 → 409
        assertApiStatus(() -> service.recordNotificationAck("INC-N-004",
                new NotificationAckRequest(requestId, "jia", "zhang", "改了")), HttpStatus.CONFLICT);
    }

    @Test
    void downgrade_blockedAtDeadlineByCritical_retryAfterAckSucceeds() {
        setupEventE("INC-N-005");
        // 11:59 yi 先回执（普通机构）
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:59:00Z"));
        assertThat(service.recordNotificationAck("INC-N-005",
                new NotificationAckRequest(key(), "yi", "wang", "yi 回执")).firstAck()).isTrue();
        // 12:00 整关键机构 jia 未回执：降级受阻，缺失清单含 jia
        ((ControllableClock) clock).setInstant(ACK_DEADLINE);
        assertThatThrownBy(() -> service.changeStatus("INC-N-005", "alice",
                new StatusRequest(key(), "CONTAINED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.details()).isEqualTo(List.of("jia"));
                });
        // 事件仍为 COMMANDING，升级记录仍 OPEN
        assertThat(service.get("INC-N-005").status()).isEqualTo("COMMANDING");
        assertThat(service.escalationHistory("INC-N-005").current().status()).isEqualTo("OPEN");

        // jia 补回执后可重新申请降级并成功
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T12:05:00Z"));
        assertThat(service.recordNotificationAck("INC-N-005",
                new NotificationAckRequest(key(), "jia", "zhang", "jia 回执")).firstAck()).isTrue();
        service.changeStatus("INC-N-005", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.get("INC-N-005").status()).isEqualTo("CONTAINED");
    }

    @Test
    void beforeDeadline_downgradeAllowed_andNormalOverdueNeverBlocks() {
        // 截止前关键机构未回执也不阻止（尚未逾期）
        setupEventE("INC-N-006");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:59:59Z"));
        service.changeStatus("INC-N-006", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.get("INC-N-006").status()).isEqualTo("CONTAINED");

        // 仅普通机构逾期的事件：降级放行，普通机构出现在待跟进清单
        ((ControllableClock) clock).setInstant(T0);
        service.report(new ReportRequest("INC-N-006-B", "S1", "仅普通机构", "r"));
        service.takeover("INC-N-006-B", "alice", new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(TRIGGER);
        service.checkEscalation("INC-N-006-B", new EscalationCheckRequest(key(), List.of(
                new NotifyAgencyRequest("yi", false, ACK_DEADLINE))));
        ((ControllableClock) clock).setInstant(ACK_DEADLINE.plusSeconds(1));
        NotificationView view = service.notificationStatus("INC-N-006-B");
        assertThat(view.overdueCritical()).isEmpty();
        assertThat(view.followUp()).containsExactly("yi");
        service.changeStatus("INC-N-006-B", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.get("INC-N-006-B").status()).isEqualTo("CONTAINED");
    }

    @Test
    void escalationAcknowledge_proceedsWithoutReceipts() {
        // 升级确认不被回执门禁阻塞
        setupEventE("INC-N-007");
        ((ControllableClock) clock).setInstant(ACK_DEADLINE.plusSeconds(30));
        service.acknowledgeEscalation("INC-N-007", "alice",
                new com.example.starter.incident.dto.Requests
                        .EscalationAckRequest(key(), "继续指挥处置"));
        assertThat(service.escalationHistory("INC-N-007").current().status())
                .isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void lateReceiptAfterDowngrade_recordedButDoesNotRewriteHistory() {
        setupEventE("INC-N-008");
        // 截止前降级成功（OPEN 升级记录被置 CANCELLED）
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:50:00Z"));
        service.changeStatus("INC-N-008", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.escalationHistory("INC-N-008").current().status()).isEqualTo("CANCELLED");
        int statusHistoryBefore = service.history("INC-N-008").statusHistory().size();

        // 降级后补回执：照常记录在机构行，但升级/降级记录不被改写
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T12:10:00Z"));
        NotificationAckResult late = service.recordNotificationAck("INC-N-008",
                new NotificationAckRequest(key(), "jia", "zhang", "迟来的回执"));
        assertThat(late.firstAck()).isTrue();
        assertThat(service.escalationHistory("INC-N-008").current().status()).isEqualTo("CANCELLED");
        assertThat(service.get("INC-N-008").status()).isEqualTo("CONTAINED");
        assertThat(service.history("INC-N-008").statusHistory()).hasSize(statusHistoryBefore);
    }

    @Test
    void checkBeforeDeadline_createsNoNotification_andReplayNeverCreatesOne() {
        service.report(new ReportRequest("INC-N-009", "S1", "x", "r"));
        service.takeover("INC-N-009", "alice", new TakeoverRequest(key()));
        // 期限前检查（携带机构清单）不创建升级也不创建通报
        ((ControllableClock) clock).setInstant(T0.plus(4, ChronoUnit.MINUTES));
        String checkKey = key();
        service.checkEscalation("INC-N-009", new EscalationCheckRequest(checkKey, List.of(
                new NotifyAgencyRequest("jia", true, ACK_DEADLINE))));
        assertApiStatus(() -> service.notificationStatus("INC-N-009"), HttpStatus.NOT_FOUND);
        // 越过遏制期限后同键重放仍不重新评估，不补建通报
        ((ControllableClock) clock).setInstant(T0.plus(10, ChronoUnit.MINUTES));
        service.checkEscalation("INC-N-009", new EscalationCheckRequest(checkKey, List.of(
                new NotifyAgencyRequest("jia", true, ACK_DEADLINE))));
        assertApiStatus(() -> service.notificationStatus("INC-N-009"), HttpStatus.NOT_FOUND);
        // 换新键检查才创建通报
        service.checkEscalation("INC-N-009", new EscalationCheckRequest(key(), List.of(
                new NotifyAgencyRequest("jia", true, ACK_DEADLINE))));
        assertThat(service.notificationStatus("INC-N-009").totalCount()).isEqualTo(1);
    }

    @Test
    void checkWithAgencies_afterEscalationExists_doesNotCreateSecondNotification() {
        setupEventE("INC-N-010");
        // 升级已存在时再次检查（携带不同机构清单）不新建通报
        service.checkEscalation("INC-N-010", new EscalationCheckRequest(key(), List.of(
                new NotifyAgencyRequest("other", true, ACK_DEADLINE))));
        NotificationView view = service.notificationStatus("INC-N-010");
        assertThat(view.totalCount()).isEqualTo(2);
        assertThat(view.agencies()).extracting(AgencyAckView::agencyKey)
                .containsExactly("jia", "yi");
    }

    @Test
    void ack_failures_doNotOccupyRequestId_andErrorBranches() {
        setupEventE("INC-N-011");
        ((ControllableClock) clock).setInstant(TRIGGER);
        String reusable = key();
        // 未知机构 → 404，不占 requestId
        assertApiStatus(() -> service.recordNotificationAck("INC-N-011",
                new NotificationAckRequest(reusable, "nobody", "z", "c")), HttpStatus.NOT_FOUND);
        // 缺字段 → 400
        assertApiStatus(() -> service.recordNotificationAck("INC-N-011",
                new NotificationAckRequest("  ", "jia", "z", "c")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.recordNotificationAck("INC-N-011",
                new NotificationAckRequest(reusable, "jia", " ", "c")), HttpStatus.BAD_REQUEST);
        // 同一 requestId 在合法机构上仍可成功（失败未占键）
        NotificationAckResult ok = service.recordNotificationAck("INC-N-011",
                new NotificationAckRequest(reusable, "jia", "zhang", "已知悉"));
        assertThat(ok.firstAck()).isTrue();

        // 事件不存在 / 通报不存在
        assertApiStatus(() -> service.recordNotificationAck("INC-404",
                new NotificationAckRequest(key(), "jia", "z", "c")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.notificationStatus("INC-404"), HttpStatus.NOT_FOUND);
        service.report(new ReportRequest("INC-N-011-RAW", "S1", "x", "r"));
        service.takeover("INC-N-011-RAW", "alice", new TakeoverRequest(key()));
        assertApiStatus(() -> service.recordNotificationAck("INC-N-011-RAW",
                new NotificationAckRequest(key(), "jia", "z", "c")), HttpStatus.CONFLICT);

        // 通报内重复机构键 → 400
        assertApiStatus(() -> service.checkEscalation("INC-N-011-RAW",
                new EscalationCheckRequest(key(), List.of(
                        new NotifyAgencyRequest("jia", true, ACK_DEADLINE),
                        new NotifyAgencyRequest("jia", false, ACK_DEADLINE)))), HttpStatus.BAD_REQUEST);
        // 缺截止时刻 → 400
        assertApiStatus(() -> service.checkEscalation("INC-N-011-RAW",
                new EscalationCheckRequest(key(), List.of(
                        new NotifyAgencyRequest("jia", true, null)))) , HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameRequestId_usedOnAnotherIncident_conflicts() {
        setupEventE("INC-N-012");
        setupEventE("INC-N-013");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:30:00Z"));
        String requestId = key();
        service.recordNotificationAck("INC-N-012",
                new NotificationAckRequest(requestId, "jia", "zhang", "已知悉"));
        // 同 requestId 打到另一事件的同机构 → 409（机构归属不同）
        assertApiStatus(() -> service.recordNotificationAck("INC-N-013",
                new NotificationAckRequest(requestId, "jia", "zhang", "已知悉")), HttpStatus.CONFLICT);
    }
}
