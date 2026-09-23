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

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 遏制逾期并发测试：验证检查、确认与遏制在事件行锁下按事务提交顺序生效——
 * 遏制先提交不再新增升级；确认与遏制竞争时升级状态不可回退，不能在取消后补确认。
 * 使用可控 Clock 将事件置于已逾期状态，不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class EscalationConcurrencyTest {

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
        jdbc.update("DELETE FROM proposal_votes");
        jdbc.update("DELETE FROM proposal_roster_entries");
        jdbc.update("DELETE FROM dependency_change_proposals");
        jdbc.update("DELETE FROM incident_dependency_edges");
        jdbc.update("UPDATE dependency_graph_meta SET graph_version = 1");
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

    private void overdueCommanding(String incidentKey) {
        service.report(new ReportRequest(incidentKey, "S1", "并发逾期", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        ((ControllableClock) clock).setInstant(T0.plus(10, ChronoUnit.MINUTES));
    }

    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
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
    void concurrentCheckAndContain_commitOrderWins_noOpenLingers() throws Exception {
        for (int round = 0; round < 5; round++) {
            String ik = "INC-C-" + round;
            overdueCommanding(ik);
            List<Object> results = runConcurrently(List.of(
                    () -> service.checkEscalation(ik, new EscalationCheckRequest(key())),
                    () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"))));
            long failures = results.stream().filter(ApiException.class::isInstance).count();
            // 两个操作语义上都可能成功（检查在非 COMMANDING 时是空结果成功），但结果必须自洽
            assertThat(failures).isZero();

            IncidentView end = service.get(ik);
            List<EscalationView> escalations = service.history(ik).escalations();
            if ("CONTAINED".equals(end.status()) && escalations.isEmpty()) {
                // 遏制先提交：检查不再新增升级
                continue;
            }
            // 检查先提交：恰好一条记录，且被同事务/随后的遏制取消，绝不残留 OPEN
            assertThat(end.status()).isEqualTo("CONTAINED");
            assertThat(escalations).hasSize(1);
            assertThat(escalations.get(0).status()).isEqualTo("CANCELLED");
        }
    }

    @Test
    void concurrentAcknowledgeAndContain_noRollbackOrLateAck() throws Exception {
        String ik = "INC-C-ACK";
        overdueCommanding(ik);
        // 先产生一条 OPEN 记录
        service.checkEscalation(ik, new EscalationCheckRequest(key()));

        List<Object> results = runConcurrently(List.of(
                () -> service.acknowledgeEscalation(ik, "alice",
                        new EscalationAckRequest(key(), "并发确认说明")),
                () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"))));

        long acks = results.stream().filter(r -> r instanceof EscalationView).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        long views = results.stream().filter(IncidentView.class::isInstance).count();
        assertThat(views).isEqualTo(1);
        assertThat(acks + conflicts).isEqualTo(1);

        EscalationView end = service.escalationHistory(ik).current();
        if (acks == 1) {
            // 确认先提交：遏制保留已确认记录，状态不回退
            assertThat(end.status()).isEqualTo("ACKNOWLEDGED");
            assertThat(end.acknowledgedBy()).isEqualTo("alice");
            assertThat(end.note()).isEqualTo("并发确认说明");
        } else {
            // 遏制先提交：记录 CANCELLED，确认失败且之后不能补确认
            assertThat(end.status()).isEqualTo("CANCELLED");
            assertThat(conflicts).isEqualTo(1);
        }
        // 终态后再确认一律 409，状态绝不回退
        try {
            service.acknowledgeEscalation(ik, "alice",
                    new EscalationAckRequest(key(), "事后补确认"));
            throw new AssertionError("遏制后不应允许确认");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
        }
        assertThat(service.escalationHistory(ik).current().status()).isEqualTo(end.status());
    }

    @Test
    void concurrentChecks_sameIncident_singleRecord() throws Exception {
        String ik = "INC-C-MULTI";
        overdueCommanding(ik);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.checkEscalation(ik, new EscalationCheckRequest(key())));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        assertThat(service.history(ik).escalations()).hasSize(1);
        assertThat(service.escalationHistory(ik).current().status()).isEqualTo("OPEN");
    }
}
