package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.NotifiedAgencyRequest;
import com.example.starter.incident.dto.Requests.NotificationReceiptRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.AgencyReceiptView;
import com.example.starter.incident.dto.Responses.EscalationHistoryView;
import com.example.starter.incident.dto.Responses.NotificationStatusView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部通报回执服务测试：覆盖事件 E（甲关键、乙普通，12:00 UTC 截止）主流程、
 * 恰好到期阻止降级、补回执后重新申请、普通机构逾期待跟进、首份有效回执不被覆盖、
 * requestId 重放/改参 409、失败不占键与机构稳定排序。时间全部由可控时钟驱动。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class NotificationServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-29T12:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_notification_receipts");
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

    private static String requestId() {
        return "REQ-" + UUID.randomUUID();
    }

    /**
     * 上报、接管并在 11:00 触发逾期升级，同时创建甲（关键）、乙（普通）通报，
     * 二者 UTC 回执截止时刻均为 12:00。
     */
    private void escalateWithJiaYi(String incidentKey) {
        ((ControllableClock) clock).setInstant(T0);
        service.report(new ReportRequest(incidentKey, "S1", "核心链路故障", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.HOURS));
        EscalationHistoryView view = service.checkEscalation(incidentKey,
                new EscalationCheckRequest(key(), List.of(
                        new NotifiedAgencyRequest("甲", true, DEADLINE),
                        new NotifiedAgencyRequest("乙", false, DEADLINE))));
        assertThat(view.current()).isNotNull();
        assertThat(view.current().status()).isEqualTo("OPEN");
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private NotificationStatusView status(String incidentKey) {
        return service.notificationStatus(incidentKey);
    }

    @Test
    void query_listsFirstReceipt_overdueAndMissing_withStableOrder() {
        escalateWithJiaYi("INC-N-1");

        // 11:30：均未回执、未逾期；缺失关键机构清单已含甲，机构顺序为甲、乙
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:30:00Z"));
        NotificationStatusView before = status("INC-N-1");
        assertThat(before.createdAt()).isEqualTo(T0.plus(1, ChronoUnit.HOURS));
        assertThat(before.agencies()).extracting(AgencyReceiptView::agencyCode)
                .containsExactly("甲", "乙");
        assertThat(before.agencies()).allSatisfy(a -> {
            assertThat(a.missing()).isTrue();
            assertThat(a.overdue()).isFalse();
            assertThat(a.signer()).isNull();
            assertThat(a.receivedAt()).isNull();
        });
        assertThat(before.missingCritical()).containsExactly("甲");
        assertThat(before.overdueCritical()).isEmpty();
        assertThat(before.followUp()).isEmpty();

        // 乙 11:40 先回执
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:40:00Z"));
        AgencyReceiptView yi = service.submitReceipt("INC-N-1",
                new NotificationReceiptRequest("乙", requestId(), "乙值班长", "已知悉"));
        assertThat(yi.missing()).isFalse();
        assertThat(yi.signer()).isEqualTo("乙值班长");
        assertThat(yi.receivedAt()).isEqualTo(Instant.parse("2026-09-29T11:40:00Z"));

        NotificationStatusView after = status("INC-N-1");
        assertThat(after.agencies()).extracting(AgencyReceiptView::agencyCode)
                .containsExactly("甲", "乙");
        assertThat(after.agencies().get(0).missing()).isTrue();
        assertThat(after.agencies().get(1).missing()).isFalse();
        assertThat(after.missingCritical()).containsExactly("甲");
        assertThat(after.followUp()).isEmpty();
    }

    @Test
    void exactlyDeadline_criticalMissing_blocksDowngrade_andShowsMissingAgency() {
        escalateWithJiaYi("INC-N-2");

        // 11:59:59 未到期：甲虽缺失但尚未逾期，可降级
        ((ControllableClock) clock).setInstant(DEADLINE.minusSeconds(1));
        service.changeStatus("INC-N-2", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.get("INC-N-2").status()).isEqualTo("CONTAINED");

        // 新事件复现 12:00 整：恰好到期视为逾期，关键机构甲缺失 → 降级受阻
        escalateWithJiaYi("INC-N-3");
        ((ControllableClock) clock).setInstant(DEADLINE);
        assertThatThrownBy(() -> service.changeStatus("INC-N-3", "alice",
                new StatusRequest(key(), "CONTAINED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("甲");
                    assertThat(e.details()).isEqualTo(List.of("甲"));
                });
        // 降级未发生
        assertThat(service.get("INC-N-3").status()).isEqualTo("COMMANDING");
        NotificationStatusView view = status("INC-N-3");
        assertThat(view.overdueCritical()).containsExactly("甲");
        // 乙未关键：同样逾期只进入待跟进，不阻断
        assertThat(view.overdueNormal()).containsExactly("乙");
        assertThat(view.followUp()).containsExactly("乙");
    }

    @Test
    void criticalAckAfterDeadline_allowsDowngradeAgain_normalOverdueOnlyFollowUp() {
        escalateWithJiaYi("INC-N-4");
        // 12:30 甲、乙均缺失：降级受阻
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T12:30:00Z"));
        assertApiStatus(() -> service.changeStatus("INC-N-4", "alice",
                new StatusRequest(key(), "CONTAINED")), HttpStatus.CONFLICT);

        // 甲补回执后可重新申请降级；乙仍逾期但普通机构只进待跟进
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T12:31:00Z"));
        AgencyReceiptView jia = service.submitReceipt("INC-N-4",
                new NotificationReceiptRequest("甲", requestId(), "甲值班长", "已接管处置"));
        assertThat(jia.missing()).isFalse();
        service.changeStatus("INC-N-4", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.get("INC-N-4").status()).isEqualTo("CONTAINED");
        NotificationStatusView view = status("INC-N-4");
        assertThat(view.overdueCritical()).isEmpty();
        assertThat(view.missingCritical()).isEmpty();
        assertThat(view.followUp()).containsExactly("乙");
        // 已降级后乙补回执不改变任何升级/降级记录，只更新其首份回执
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T13:00:00Z"));
        service.submitReceipt("INC-N-4",
                new NotificationReceiptRequest("乙", requestId(), "乙值班长", "迟到回执"));
        assertThat(status("INC-N-4").agencies().get(1).receivedAt())
                .isEqualTo(Instant.parse("2026-09-29T13:00:00Z"));
        assertThat(service.get("INC-N-4").status()).isEqualTo("CONTAINED");
    }

    @Test
    void firstValidReceiptWins_duplicatesNeitherCountNorOverwrite() {
        escalateWithJiaYi("INC-N-5");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:40:00Z"));
        String firstRequest = requestId();
        AgencyReceiptView first = service.submitReceipt("INC-N-5",
                new NotificationReceiptRequest("甲", firstRequest, "甲值班长", "首份内容"));
        assertThat(first.signer()).isEqualTo("甲值班长");
        assertThat(first.receivedAt()).isEqualTo(Instant.parse("2026-09-29T11:40:00Z"));

        // 换 requestId 的后续报文不得计入：409
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:50:00Z"));
        assertApiStatus(() -> service.submitReceipt("INC-N-5",
                new NotificationReceiptRequest("甲", requestId(), "冒名签收人", "覆盖内容")),
                HttpStatus.CONFLICT);

        // 完成数仍为 1，首份签收人与时刻不被覆盖
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_receipts r"
                        + " JOIN incident_notification_agencies a ON a.id = r.agency_id"
                        + " WHERE a.agency_code = '甲' AND r.incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key = 'INC-N-5')",
                Integer.class);
        assertThat(count).isEqualTo(1);
        AgencyReceiptView jia = status("INC-N-5").agencies().get(0);
        assertThat(jia.signer()).isEqualTo("甲值班长");
        assertThat(jia.receivedAt()).isEqualTo(Instant.parse("2026-09-29T11:40:00Z"));
    }

    @Test
    void sameRequestId_replaysOriginal_changeAgencyOrContent_conflicts() {
        escalateWithJiaYi("INC-N-6");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:40:00Z"));
        String rid = requestId();
        AgencyReceiptView first = service.submitReceipt("INC-N-6",
                new NotificationReceiptRequest("甲", rid, "甲值班长", "内容A"));

        // 时钟走到逾期后，同 requestId 原报文重放首次响应（receivedAt 仍为 11:40）
        ((ControllableClock) clock).setInstant(DEADLINE.plusSeconds(60));
        AgencyReceiptView replay = service.submitReceipt("INC-N-6",
                new NotificationReceiptRequest("甲", rid, "甲值班长", "内容A"));
        assertThat(replay).isEqualTo(first);
        assertThat(replay.receivedAt()).isEqualTo(Instant.parse("2026-09-29T11:40:00Z"));

        // 换机构 / 改内容 / 改签收人 均 409
        assertApiStatus(() -> service.submitReceipt("INC-N-6",
                new NotificationReceiptRequest("乙", rid, "甲值班长", "内容A")), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.submitReceipt("INC-N-6",
                new NotificationReceiptRequest("甲", rid, "甲值班长", "内容B")), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.submitReceipt("INC-N-6",
                new NotificationReceiptRequest("甲", rid, "别人", "内容A")), HttpStatus.CONFLICT);
    }

    @Test
    void failureDoesNotOccupyRequestId_andUnknownAgency404() {
        escalateWithJiaYi("INC-N-7");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T11:40:00Z"));

        // 不存在的机构 → 404，requestId 不被占用
        String reusable = requestId();
        assertApiStatus(() -> service.submitReceipt("INC-N-7",
                new NotificationReceiptRequest("丙", reusable, "x", "y")), HttpStatus.NOT_FOUND);
        AgencyReceiptView jia = service.submitReceipt("INC-N-7",
                new NotificationReceiptRequest("甲", reusable, "甲值班长", "首份"));
        assertThat(jia.missing()).isFalse();

        // 甲已有回执后，新 requestId 再投甲 → 409 且不占用；该 requestId 可用于乙的首份回执
        String second = requestId();
        assertApiStatus(() -> service.submitReceipt("INC-N-7",
                new NotificationReceiptRequest("甲", second, "甲值班长", "再来")), HttpStatus.CONFLICT);
        AgencyReceiptView yi = service.submitReceipt("INC-N-7",
                new NotificationReceiptRequest("乙", second, "乙值班长", "乙首份"));
        assertThat(yi.missing()).isFalse();
    }

    @Test
    void blankFields_badRequest_andNoNotificationAgenciesMeansNoGate() {
        // 空字段 400
        escalateWithJiaYi("INC-N-8");
        assertApiStatus(() -> service.submitReceipt("INC-N-8",
                new NotificationReceiptRequest("  ", requestId(), "s", "c")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.submitReceipt("INC-N-8",
                new NotificationReceiptRequest("甲", " ", "s", "c")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.submitReceipt("INC-N-8",
                new NotificationReceiptRequest("甲", requestId(), " ", "c")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.submitReceipt("INC-N-8",
                new NotificationReceiptRequest("甲", requestId(), "s", null)), HttpStatus.BAD_REQUEST);

        // 升级检查不携带机构：不创建通报，降级不受回执门禁影响，查询为空骨架
        service.report(new ReportRequest("INC-N-9", "S1", "x", "r"));
        service.takeover("INC-N-9", "alice", new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(Instant.parse("2026-09-29T13:00:00Z"));
        service.checkEscalation("INC-N-9", new EscalationCheckRequest(key()));
        service.changeStatus("INC-N-9", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.get("INC-N-9").status()).isEqualTo("CONTAINED");
        NotificationStatusView empty = status("INC-N-9");
        assertThat(empty.createdAt()).isNull();
        assertThat(empty.agencies()).isEmpty();

        // 通报查询不存在的事件 → 404
        assertApiStatus(() -> service.notificationStatus("INC-404"), HttpStatus.NOT_FOUND);
    }

    @Test
    void escalationCheck_isIdempotentForAgencySet() {
        escalateWithJiaYi("INC-N-10");
        // 同键带相同机构重放：不重复创建通报与机构
        String ck = key();
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.HOURS));
        service.checkEscalation("INC-N-10", new EscalationCheckRequest(ck, List.of(
                new NotifiedAgencyRequest("甲", true, DEADLINE),
                new NotifiedAgencyRequest("乙", false, DEADLINE))));
        Integer agencies = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_agencies", Integer.class);
        assertThat(agencies).isEqualTo(2);
        // 同键改机构集合（改甲截止时刻）→ 409
        assertApiStatus(() -> service.checkEscalation("INC-N-10",
                new EscalationCheckRequest(ck, List.of(
                        new NotifiedAgencyRequest("甲", true, DEADLINE.plusSeconds(1)),
                        new NotifiedAgencyRequest("乙", false, DEADLINE)))), HttpStatus.CONFLICT);
    }
}
