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

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 联合交接并发边界测试：接受与任务完成、升级确认、并发接受在事件行锁与
 * 交接单行锁下按事务提交顺序生效；同键并发接受只生效一次。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class HandoverConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private HandoverService handovers;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_handover_incidents");
        jdbc.update("DELETE FROM incident_handovers");
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

    private void commanding(String incidentKey, String commander) {
        incidents.report(new ReportRequest(incidentKey, "S2", "故障 " + incidentKey, "reporter-1"));
        incidents.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private HandoverDetailView initiate(String handoverKey, List<String> incidentKeys) {
        return handovers.initiate("alice",
                new HandoverInitiateRequest(key(), handoverKey, "bob", incidentKeys));
    }

    /**
     * 并发提交一批任务并收集结果（成功值或异常）。
     */
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
    void concurrentAcceptAndTaskComplete_commitOrderWins() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        incidents.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-A", "G1", "任务", List.of()));
        HandoverDetailView initiated = initiate("HO-C1", List.of("INC-A", "INC-B"));

        List<Object> results = runConcurrently(List.of(
                () -> handovers.accept("HO-C1", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                () -> incidents.completeTask("INC-A", "T-A", "alice",
                        new TaskActionRequest(key()))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        if ("ACCEPTED".equals(handovers.getHandover("HO-C1").handover().status())) {
            // 接受先提交：任务完成因指挥人已切换而失败，闭包全部事件指挥人为 bob
            assertThat(incidents.get("INC-A").commander()).isEqualTo("bob");
            assertThat(incidents.get("INC-B").commander()).isEqualTo("bob");
            assertThat(incidents.getTask("INC-A", "T-A").status()).isEqualTo("OPEN");
        } else {
            // 任务完成先提交：接受因版本变化而 409，指挥人不变，无部分接管
            assertThat(incidents.getTask("INC-A", "T-A").status()).isEqualTo("DONE");
            assertThat(incidents.get("INC-A").commander()).isEqualTo("alice");
            assertThat(incidents.get("INC-B").commander()).isEqualTo("alice");
        }
    }

    @Test
    void concurrentAccepts_exactlyOneWins() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        HandoverDetailView initiated = initiate("HO-C2", List.of("INC-A", "INC-B"));

        List<Callable<HandoverDetailView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> handovers.accept("HO-C2", "bob",
                    new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                            initiated.summary())));
        }
        List<Object> results = runConcurrently(calls);

        long successes = results.stream().filter(HandoverDetailView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(2);
        assertThat(handovers.getHandover("HO-C2").handover().status()).isEqualTo("ACCEPTED");
        assertThat(incidents.get("INC-A").commander()).isEqualTo("bob");
        assertThat(incidents.get("INC-B").commander()).isEqualTo("bob");
    }

    @Test
    void concurrentAcceptAndEscalationAck_commitOrderWins() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(16 * 60));
        incidents.checkEscalation("INC-A", new EscalationCheckRequest(key()));
        HandoverDetailView initiated = initiate("HO-C3", List.of("INC-A", "INC-B"));

        List<Object> results = runConcurrently(List.of(
                () -> handovers.accept("HO-C3", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                () -> incidents.acknowledgeEscalation("INC-A", "alice",
                        new EscalationAckRequest(key(), "已处理"))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long failures = results.stream().filter(ApiException.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(failures).isEqualTo(1);

        if ("ACCEPTED".equals(handovers.getHandover("HO-C3").handover().status())) {
            // 接受先提交：旧指挥人失去确认权
            assertThat(incidents.get("INC-A").commander()).isEqualTo("bob");
            assertThat(incidents.escalationHistory("INC-A").current().status()).isEqualTo("OPEN");
        } else {
            // 确认先提交：接受因未确认升级集合变化而 409
            assertThat(incidents.escalationHistory("INC-A").current().status())
                    .isEqualTo("ACKNOWLEDGED");
            assertThat(incidents.get("INC-A").commander()).isEqualTo("alice");
        }
    }

    @Test
    void concurrentSameCommandKeyAccept_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        HandoverDetailView initiated = initiate("HO-C4", List.of("INC-A", "INC-B"));

        String commandKey = key();
        List<Callable<HandoverDetailView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> handovers.accept("HO-C4", "bob",
                    new HandoverAcceptRequest(commandKey, initiated.handoverVersion(),
                            initiated.summary())));
        }
        List<Object> results = runConcurrently(calls);

        List<HandoverDetailView> successes = results.stream()
                .filter(HandoverDetailView.class::isInstance)
                .map(HandoverDetailView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且接受只生效一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(handovers.getHandover("HO-C4").handover().status()).isEqualTo("ACCEPTED");
        assertThat(incidents.get("INC-A").commander()).isEqualTo("bob");
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }
}
