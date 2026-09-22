package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.example.starter.incident.dto.Responses.EscalationCheckView;
import com.example.starter.incident.dto.Responses.EscalationView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级相关并发测试：在真实 H2（MODE=MySQL）上验证检查、确认与遏制并发时
 * 按事务提交顺序生效——遏制先提交不再新增升级，确认与遏制互斥，同键/并发检查只产生一条记录。
 */
@SpringBootTest
@Import(EscalationConcurrencyTest.TestClockConfig.class)
class EscalationConcurrencyTest {

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

    /** S1 事件于 T0 由 alice 接管；测试开始时把时钟拨到逾期之后。 */
    private String overdueIncident(String incidentKey) {
        clock.set(T0);
        service.report(new ReportRequest(incidentKey, "S1", "并发升级", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        clock.set(T0.plus(6, ChronoUnit.MINUTES));
        return incidentKey;
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
    void concurrentChecks_distinctKeys_singleOpenRecord() throws Exception {
        String ik = overdueIncident("INC-E200");
        List<Callable<EscalationCheckView>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.checkEscalation(ik, new EscalationCheckRequest(key())));
        }
        List<Object> results = runConcurrently(tasks);

        long created = results.stream().filter(EscalationCheckView.class::isInstance)
                .map(EscalationCheckView.class::cast)
                .filter(EscalationCheckView::created).count();
        assertThat(created).isEqualTo(1);
        assertThat(service.escalations(ik).history()).hasSize(1);
        assertThat(service.escalations(ik).current().status()).isEqualTo("OPEN");
    }

    @Test
    void concurrentCheckAndContain_commitOrderWins_noOpenAfterContain() throws Exception {
        String ik = overdueIncident("INC-E201");
        List<Object> results = runConcurrently(List.of(
                () -> service.checkEscalation(ik, new EscalationCheckRequest(key())),
                () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"))));

        long successes = results.stream()
                .filter(r -> r instanceof EscalationCheckView || r instanceof IncidentView).count();
        assertThat(successes).isEqualTo(2);

        IncidentView end = service.get(ik);
        assertThat(end.status()).isEqualTo("CONTAINED");
        var history = service.escalations(ik).history();
        // 遏制先提交 → 不新增记录；检查先提交 → 记录被原子 CANCELLED；绝不允许残留 OPEN
        assertThat(history).hasSizeLessThanOrEqualTo(1);
        if (history.size() == 1) {
            assertThat(history.get(0).status()).isEqualTo("CANCELLED");
        }
    }

    @Test
    void concurrentAckAndContain_commitOrderDeterminesFinalState() throws Exception {
        String ik = overdueIncident("INC-E202");
        service.checkEscalation(ik, new EscalationCheckRequest(key()));

        List<Object> results = runConcurrently(List.of(
                () -> service.acknowledgeEscalation(ik, "alice",
                        new EscalationAckRequest(key(), "并发确认说明")),
                () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"))));

        Object ackResult = results.get(0);
        Object containResult = results.get(1);
        // 遏制必然成功（保留已确认记录）
        assertThat(containResult).isInstanceOf(IncidentView.class);
        assertThat(((IncidentView) containResult).status()).isEqualTo("CONTAINED");

        String finalStatus = service.escalations(ik).current().status();
        if (ackResult instanceof EscalationView v) {
            // 确认先提交：确认成功，遏制随后保留 ACKNOWLEDGED
            assertThat(v.status()).isEqualTo("ACKNOWLEDGED");
            assertThat(finalStatus).isEqualTo("ACKNOWLEDGED");
        } else {
            // 遏制先提交：OPEN 已原子 CANCELLED，确认失败且不能补确认
            assertThat(ackResult).isInstanceOf(ApiException.class);
            assertThat(((ApiException) ackResult).status().value()).isEqualTo(409);
            assertThat(finalStatus).isEqualTo("CANCELLED");
        }
    }

    @Test
    void concurrentSameCheckKey_singleConsistentResult() throws Exception {
        String ik = overdueIncident("INC-E203");
        String commandKey = key();
        List<Callable<EscalationCheckView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.checkEscalation(ik, new EscalationCheckRequest(commandKey)));
        }
        List<Object> results = runConcurrently(tasks);

        List<EscalationCheckView> views = results.stream()
                .filter(EscalationCheckView.class::isInstance)
                .map(EscalationCheckView.class::cast).toList();
        assertThat(views).isNotEmpty();
        assertThat(views).allSatisfy(v -> assertThat(v).isEqualTo(views.get(0)));
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(keyCount).isEqualTo(1);
        assertThat(service.escalations(ik).history()).hasSize(1);
    }
}
