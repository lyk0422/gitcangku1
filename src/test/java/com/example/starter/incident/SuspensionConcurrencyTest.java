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
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResumeRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.SuspendRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.SuspensionHistoryView;
import com.example.starter.incident.dto.Responses.SuspensionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 遏制时限挂起并发测试（真实 H2 MySQL 兼容库）：挂起、恢复、遏制登记与逾期检查
 * 均在事件行锁（SELECT ... FOR UPDATE）下按事务提交顺序裁决。
 * 验证：重复挂起只有一条生效区间；恢复/遏制竞争区间恰好封口一次且不残留生效区间；
 * 挂起与逾期检查同刻竞争结果自洽；同一 commandKey 并发重放返回同一首次结果。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class SuspensionConcurrencyTest {

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

    private void commanding(String incidentKey) {
        service.report(new ReportRequest(incidentKey, "S1", "并发挂起", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
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
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDoubleSuspend_singleOpenInterval_oneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            String ik = "INC-SC-S-" + round;
            commanding(ik);
            ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.MINUTES));
            List<Object> results = runConcurrently(List.of(
                    () -> service.suspend(ik, "alice", new SuspendRequest(key(), "KA", "原因A")),
                    () -> service.suspend(ik, "alice", new SuspendRequest(key(), "KB", "原因B"))));
            long winners = results.stream().filter(SuspensionView.class::isInstance).count();
            long conflicts = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
            assertThat(winners).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            SuspensionHistoryView view = service.suspensionHistory(ik);
            assertThat(view.suspended()).isTrue();
            assertThat(view.intervals()).hasSize(1);
            assertThat(view.intervals().get(0).resumedAt()).isNull();
        }
    }

    @Test
    void concurrentResumeAndContain_intervalSealedOnce_noOpenLingers() throws Exception {
        String ik = "INC-SC-RC";
        commanding(ik);
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.MINUTES));
        service.suspend(ik, "alice", new SuspendRequest(key(), "SK", "等待外部"));
        ((ControllableClock) clock).setInstant(T0.plus(3, ChronoUnit.MINUTES));

        List<Object> results = runConcurrently(List.of(
                () -> service.resume(ik, "alice", new ResumeRequest(key(), "SK", "恢复计时")),
                () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"))));

        long resumes = results.stream().filter(SuspensionView.class::isInstance).count();
        long incidents = results.stream()
                .filter(r -> r instanceof com.example.starter.incident.dto.Responses.IncidentView)
                .count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        assertThat(incidents).isEqualTo(1);
        // 恢复先提交则成功；遏制先提交则区间被遏制封口、恢复 409
        assertThat(resumes + conflicts).isEqualTo(1);

        // 终态自洽：事件已遏制，区间恰好封口一次，绝不残留生效中挂起
        assertThat(service.get(ik).status()).isEqualTo("CONTAINED");
        SuspensionHistoryView end = service.suspensionHistory(ik);
        assertThat(end.suspended()).isFalse();
        assertThat(end.intervals()).hasSize(1);
        assertThat(end.intervals().get(0).resumedAt()).isEqualTo(T0.plus(3, ChronoUnit.MINUTES));
        assertThat(end.intervals().get(0).resumedBy()).isEqualTo("alice");
        // 已遏制后再恢复一律 409
        try {
            service.resume(ik, "alice", new ResumeRequest(key(), "SK", "事后恢复"));
            throw new AssertionError("遏制后不应允许恢复");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
        }
    }

    @Test
    void concurrentSuspendAndOverdueCheck_commitOrderConsistent() throws Exception {
        for (int round = 0; round < 5; round++) {
            String ik = "INC-SC-SE-" + round;
            commanding(ik);
            // 同一时刻 T0+6m（已过原始 5m 期限）竞争：挂起先提交则检查按挂起后口径不触发
            ((ControllableClock) clock).setInstant(T0.plus(6, ChronoUnit.MINUTES));
            List<Object> results = runConcurrently(List.of(
                    () -> service.suspend(ik, "alice", new SuspendRequest(key(), "SK", "挂起")),
                    () -> service.checkEscalation(ik, new EscalationCheckRequest(key()))));
            assertThat(results).noneMatch(Exception.class::isInstance);

            boolean suspended = service.suspensionHistory(ik).suspended();
            var escalation = service.escalationHistory(ik).current();
            if (suspended && escalation == null) {
                // 挂起先提交：生效区间冻结，同刻检查不触发升级
                continue;
            }
            // 检查先提交：已逾期产生 OPEN 升级（OPEN 未确认不禁止随后挂起），二者共存且自洽
            assertThat(suspended).isTrue();
            assertThat(escalation).isNotNull();
            assertThat(escalation.status()).isEqualTo("OPEN");
        }
    }

    @Test
    void concurrentSameCommandKey_suspendReplaysFirstResult_singleInterval() throws Exception {
        String ik = "INC-SC-IDEM";
        commanding(ik);
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.MINUTES));
        String sharedKey = key();
        List<Object> results = runConcurrently(List.of(
                () -> service.suspend(ik, "alice", new SuspendRequest(sharedKey, "SK", "同键原因")),
                () -> service.suspend(ik, "alice", new SuspendRequest(sharedKey, "SK", "同键原因"))));
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(SuspensionView.class));
        SuspensionView first = (SuspensionView) results.get(0);
        SuspensionView second = (SuspensionView) results.get(1);
        assertThat(first.id()).isEqualTo(second.id());
        assertThat(service.suspensionHistory(ik).intervals()).hasSize(1);
    }
}
