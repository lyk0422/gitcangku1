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
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResumeRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.SuspendRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.SuspensionStatusView;
import com.example.starter.incident.dto.Responses.SuspensionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 遏制时限挂起并发测试：验证挂起/恢复/逾期检查/遏制登记在事件行锁下按事务
 * 提交顺序裁决——同时至多一个生效挂起；恢复只封口一次；挂起先提交则逾期判定
 * 按挂起后口径（不触发升级）；遏制先提交则挂起被拒绝或区间被遏制封口。
 * 使用可控 Clock，不依赖真实睡眠。
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

    private static long conflicts(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
    }

    @Test
    void concurrentSuspends_singleActiveSuspension() throws Exception {
        commanding("INC-P-001");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String suspendKey = "SK-" + i;
            tasks.add(() -> service.suspend("INC-P-001", "alice",
                    new SuspendRequest(key(), suspendKey, "并发挂起")));
        }
        List<Object> results = runConcurrently(tasks);
        long successes = results.stream().filter(SuspensionView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts(results)).isEqualTo(3);
        // 同时至多一个生效挂起
        SuspensionStatusView status = service.suspensionStatus("INC-P-001");
        assertThat(status.suspended()).isTrue();
        assertThat(status.suspensions()).hasSize(1);
    }

    @Test
    void concurrentResumes_intervalSealedExactlyOnce() throws Exception {
        commanding("INC-P-002");
        service.suspend("INC-P-002", "alice", new SuspendRequest(key(), "SK-1", "等待"));
        List<Object> results = runConcurrently(List.of(
                () -> service.resume("INC-P-002", "alice",
                        new ResumeRequest(key(), "SK-1", "恢复甲")),
                () -> service.resume("INC-P-002", "alice",
                        new ResumeRequest(key(), "SK-1", "恢复乙"))));
        long successes = results.stream().filter(SuspensionView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts(results)).isEqualTo(1);
        // 区间只封口一次，起始半区保持首次落库值
        SuspensionStatusView status = service.suspensionStatus("INC-P-002");
        assertThat(status.suspended()).isFalse();
        assertThat(status.suspensions()).hasSize(1);
        SuspensionView sealed = status.suspensions().get(0);
        assertThat(sealed.suspendedAt()).isEqualTo(T0);
        assertThat(sealed.resumedAt()).isEqualTo(T0);
        assertThat(sealed.reason()).isEqualTo("等待");
    }

    @Test
    void concurrentSuspendAndEscalationCheck_commitOrderDecidesOutcome() throws Exception {
        for (int round = 0; round < 5; round++) {
            String ik = "INC-P-010-" + round;
            commanding(ik);
            // 已越过原始期限（S1=300 秒）
            ((ControllableClock) clock).setInstant(T0.plusSeconds(600));
            List<Object> results = runConcurrently(List.of(
                    () -> service.suspend(ik, "alice", new SuspendRequest(key(), "SK-1", "挂起")),
                    () -> service.checkEscalation(ik, new EscalationCheckRequest(key()))));
            // 两个操作语义上都可成功，结果必须自洽
            assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));

            SuspensionStatusView status = service.suspensionStatus(ik);
            var escalation = service.escalationHistory(ik).current();
            assertThat(status.suspended()).isTrue();
            if (escalation == null) {
                // 挂起先提交：逾期判定按挂起后口径，不触发升级
                continue;
            }
            // 检查先提交：已产生 OPEN 记录，挂起仍允许（仅已确认才禁止挂起）
            assertThat(escalation.status()).isEqualTo("OPEN");
        }
    }

    @Test
    void concurrentSuspendAndContain_commitOrderDecidesOutcome() throws Exception {
        for (int round = 0; round < 5; round++) {
            String ik = "INC-P-020-" + round;
            commanding(ik);
            ((ControllableClock) clock).setInstant(T0.plusSeconds(60));
            List<Object> results = runConcurrently(List.of(
                    () -> service.suspend(ik, "alice", new SuspendRequest(key(), "SK-1", "挂起")),
                    () -> service.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"))));

            assertThat(service.get(ik).status()).isEqualTo("CONTAINED");
            SuspensionStatusView status = service.suspensionStatus(ik);
            long suspends = results.stream().filter(SuspensionView.class::isInstance).count();
            if (suspends == 1) {
                // 挂起先提交：遏制登记将生效区间以遏制时刻封口
                assertThat(status.suspensions()).hasSize(1);
                assertThat(status.suspended()).isFalse();
                assertThat(status.suspensions().get(0).resumedAt())
                        .isEqualTo(T0.plusSeconds(60));
            } else {
                // 遏制先提交：挂起被拒绝（409），无挂起区间
                assertThat(conflicts(results)).isEqualTo(1);
                assertThat(status.suspensions()).isEmpty();
            }
        }
    }
}
