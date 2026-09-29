package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.example.starter.incident.dto.Requests.NotifiedAgencyRequest;
import com.example.starter.incident.dto.Requests.NotificationReceiptRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.AgencyReceiptView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部通报回执并发测试：回执与降级同时到达时，二者在同一事件行锁下串行，
 * 以事务提交时已生效的回执状态决定降级是否放行；同机构重复回执只有首份计入；
 * 同 requestId 并发均重放首份结果。使用可控时钟将时刻固定在恰好到期点，不靠睡眠。
 * 时间窗单调递增，避免回退共享时钟影响仍在提交的工作线程。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class NotificationConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

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

    private void setupAtDeadline(String incidentKey, Instant base) {
        Instant deadline = base.plus(2, ChronoUnit.HOURS);
        ((ControllableClock) clock).setInstant(base);
        service.report(new ReportRequest(incidentKey, "S1", "核心链路故障", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(base.plus(1, ChronoUnit.HOURS));
        service.checkEscalation(incidentKey, new EscalationCheckRequest(key(), List.of(
                new NotifiedAgencyRequest("甲", true, deadline),
                new NotifiedAgencyRequest("乙", false, deadline))));
        // 竞争窗口固定在恰好到期时刻，逾期判定对双方一致
        ((ControllableClock) clock).setInstant(deadline);
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
                    start.await(5, TimeUnit.SECONDS);
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
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int receiptCount(String incidentKey, String agencyCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notification_receipts WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key = ?) AND agency_code = ?",
                Integer.class, incidentKey, agencyCode);
        return count == null ? 0 : count;
    }

    @Test
    void receiptAndDowngradeRace_commitOrderDecides_stateAlwaysConsistent() throws Exception {
        for (int round = 0; round < 8; round++) {
            String ik = "INC-NC-" + round;
            // 每轮使用单调递增的时间窗，绝不回退共享时钟
            setupAtDeadline(ik, T0.plus(round * 6L, ChronoUnit.HOURS));
            String rid = key();
            List<Object> results = runConcurrently(List.of(
                    () -> service.submitReceipt(ik,
                            new NotificationReceiptRequest("甲", rid, "甲值班长", "并发回执")),
                    () -> service.changeStatus(ik, "alice",
                            new StatusRequest(key(), "CONTAINED"))));

            long receipts = results.stream().filter(AgencyReceiptView.class::isInstance).count();
            long downgradeViews = results.stream().filter(IncidentView.class::isInstance).count();
            long conflicts = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
            assertThat(receipts).as("results=%s", results).isEqualTo(1);
            assertThat(downgradeViews + conflicts).isEqualTo(1);

            // 甲只有一条回执，完成数不随并发增加
            assertThat(receiptCount(ik, "甲")).isEqualTo(1);

            IncidentView end = service.get(ik);
            if (downgradeViews == 1) {
                // 回执先提交：降级放行，事件已遏制
                assertThat(end.status()).isEqualTo("CONTAINED");
            } else {
                // 降级先提交：甲当时缺失且恰好逾期，降级受阻；事件仍 COMMANDING；
                // 回执随后已生效，可重新申请降级并成功
                assertThat(end.status()).isEqualTo("COMMANDING");
                IncidentView retry = service.changeStatus(ik, "alice",
                        new StatusRequest(key(), "CONTAINED"));
                assertThat(retry.status()).isEqualTo("CONTAINED");
            }
        }
    }

    @Test
    void duplicateReceiptsSameAgencyConcurrently_onlyFirstCounts() throws Exception {
        String ik = "INC-NC-DUP";
        Instant base = T0.plus(1, ChronoUnit.DAYS);
        Instant deadline = base.plus(2, ChronoUnit.HOURS);
        setupAtDeadline(ik, base);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            tasks.add(() -> service.submitReceipt(ik, new NotificationReceiptRequest(
                    "甲", key(), "签收人" + n, "内容" + n)));
        }
        List<Object> results = runConcurrently(tasks);
        long ok = results.stream().filter(AgencyReceiptView.class::isInstance).count();
        long conflict = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(3);
        // 只有首份有效回执落库
        assertThat(receiptCount(ik, "甲")).isEqualTo(1);
        AgencyReceiptView jia = service.notificationStatus(ik).agencies().get(0);
        assertThat(jia.missing()).isFalse();
        assertThat(jia.receivedAt()).isEqualTo(deadline);
    }

    @Test
    void sameRequestIdConcurrently_bothReplayFirstResult_singleRow() throws Exception {
        String ik = "INC-NC-RID";
        Instant base = T0.plus(2, ChronoUnit.DAYS);
        setupAtDeadline(ik, base);
        String rid = key();
        List<Object> results = runConcurrently(List.of(
                () -> service.submitReceipt(ik,
                        new NotificationReceiptRequest("甲", rid, "甲值班长", "相同内容")),
                () -> service.submitReceipt(ik,
                        new NotificationReceiptRequest("甲", rid, "甲值班长", "相同内容"))));
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        AgencyReceiptView first = (AgencyReceiptView) results.get(0);
        AgencyReceiptView second = (AgencyReceiptView) results.get(1);
        assertThat(first).isEqualTo(second);
        assertThat(receiptCount(ik, "甲")).isEqualTo(1);
    }
}
