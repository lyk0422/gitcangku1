package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.NotificationAckRequest;
import com.example.starter.incident.dto.Requests.NotifyAgencyRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.AgencyAckView;
import com.example.starter.incident.dto.Responses.NotificationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部通报回执并发测试（真实 H2 + 真实多线程）：
 * 回执与降级同时到达时，以事务提交时已生效的回执状态决定降级（事件行锁串行化）；
 * 同机构并发回执仅一份计首份；同 requestId 并发仅一份成功；跨事件同 requestId 由
 * 全局唯一约束裁决，失败方返回 409 而非 500。时钟冻结在截止时刻，不依赖睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class NotificationConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-29T11:00:00Z");
    private static final Instant TRIGGER = Instant.parse("2026-09-29T11:06:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-29T12:00:00Z");

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

    private void setupEventE(String incidentKey) {
        ((ControllableClock) clock).setInstant(T0);
        service.report(new ReportRequest(incidentKey, "S1", "事件E", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(TRIGGER);
        service.checkEscalation(incidentKey, new EscalationCheckRequest(key(), List.of(
                new NotifyAgencyRequest("yi", false, DEADLINE),
                new NotifyAgencyRequest("jia", true, DEADLINE))));
        // 冻结在恰好到期时刻：jia 未回执即逾期
        ((ControllableClock) clock).setInstant(DEADLINE);
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ackAndDowngradeRace_decidedByCommittedState() throws Exception {
        for (int round = 0; round < 5; round++) {
            String ik = "INC-RACE-" + round;
            setupEventE(ik);
            String ackRequestId = key();
            List<Object> results = runConcurrently(List.of(
                    () -> service.recordNotificationAck(ik,
                            new NotificationAckRequest(ackRequestId, "jia", "zhang", "已知悉")),
                    () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"))));

            Object ackResult = results.get(0);
            Object downgradeResult = results.get(1);
            assertThat(ackResult).isInstanceOf(NotificationAckResult.class);
            assertThat(((NotificationAckResult) ackResult).firstAck()).isTrue();

            // 回执必然落库且为 jia 首份
            NotificationView finalView = service.notificationStatus(ik);
            AgencyAckView jia = finalView.agencies().stream()
                    .filter(a -> a.agencyKey().equals("jia")).findFirst().orElseThrow();
            assertThat(jia.acknowledged()).isTrue();
            assertThat(jia.firstRequestId()).isEqualTo(ackRequestId);

            if (downgradeResult instanceof com.example.starter.incident.dto.Responses.IncidentView) {
                // 回执先提交：降级放行成功
                assertThat(service.get(ik).status()).isEqualTo("CONTAINED");
            } else {
                // 降级先提交：按当时状态（jia 未回执）阻止，缺失清单为 jia
                assertThat(downgradeResult).isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.details()).isEqualTo(List.of("jia"));
                });
                assertThat(service.get(ik).status()).isEqualTo("COMMANDING");
                // 补回执已生效后，重新申请降级必须成功
                service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
                assertThat(service.get(ik).status()).isEqualTo("CONTAINED");
            }
            // 原始回执仅一份
            Integer receipts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incident_notification_acks WHERE request_id = ?",
                    Integer.class, ackRequestId);
            assertThat(receipts).isEqualTo(1);
        }
    }

    @Test
    void concurrentDuplicateAcks_sameAgency_onlyOneCountsFirst() throws Exception {
        String ik = "INC-RACE-DUP";
        setupEventE(ik);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            tasks.add(() -> service.recordNotificationAck(ik,
                    new NotificationAckRequest(key(), "jia", "signer-" + n, "内容-" + n)));
        }
        List<Object> results = runConcurrently(tasks);
        long firstCount = results.stream()
                .filter(NotificationAckResult.class::isInstance)
                .map(NotificationAckResult.class::cast)
                .filter(NotificationAckResult::firstAck).count();
        assertThat(firstCount).isEqualTo(1);
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));

        NotificationView view = service.notificationStatus(ik);
        assertThat(view.acknowledgedCount()).isEqualTo(1);
        // 四份不同报文均留存，但机构行只有一条首份记录
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_notification_acks", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_agencies WHERE agency_key='jia'"
                        + " AND received_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestId_singleRow_allReplayOriginal() throws Exception {
        String ik = "INC-RACE-RID";
        setupEventE(ik);
        String requestId = key();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.recordNotificationAck(ik,
                    new NotificationAckRequest(requestId, "jia", "zhang", "已知悉")));
        }
        List<Object> results = runConcurrently(tasks);
        // 同 requestId 同机构同内容：全部重放首次成功响应（1 次插入 + 3 次重放），无 409
        long success = results.stream().filter(NotificationAckResult.class::isInstance).count();
        assertThat(success).isEqualTo(4);
        assertThat(results).allSatisfy(r -> {
            assertThat(r).isInstanceOf(NotificationAckResult.class);
            NotificationView view = ((NotificationAckResult) r).view();
            assertThat(view.acknowledgedCount()).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_acks WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestId_conflictingContent_onlyOriginalWins() throws Exception {
        // 同 requestId 但签收人/内容不同并发：唯一约束裁决，仅 1 份成功，其余 409
        String ik = "INC-RACE-RID2";
        setupEventE(ik);
        String requestId = key();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            tasks.add(() -> service.recordNotificationAck(ik,
                    new NotificationAckRequest(requestId, "jia", "signer-" + n, "内容-" + n)));
        }
        List<Object> results = runConcurrently(tasks);
        long success = results.stream().filter(NotificationAckResult.class::isInstance).count();
        long conflict = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_acks WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestId_differentIncidents_loserGetsConflictNot500() throws Exception {
        setupEventE("INC-RACE-X1");
        setupEventE("INC-RACE-X2");
        String requestId = key();
        List<Object> results = runConcurrently(List.of(
                () -> service.recordNotificationAck("INC-RACE-X1",
                        new NotificationAckRequest(requestId, "jia", "zhang", "已知悉")),
                () -> service.recordNotificationAck("INC-RACE-X2",
                        new NotificationAckRequest(requestId, "jia", "zhang", "已知悉"))));
        long success = results.stream().filter(NotificationAckResult.class::isInstance).count();
        long conflict = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_acks WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }
}
