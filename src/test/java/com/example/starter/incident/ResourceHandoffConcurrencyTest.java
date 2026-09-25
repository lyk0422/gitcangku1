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

import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffItemRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAcquireRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.HandoffSettleView;
import com.example.starter.incident.dto.Responses.HandoffView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 互助交接并发边界测试：验证同 handoffKey 并发单次生效、同资源并发借出唯一成交、
 * 任务开始与租约结算按提交顺序裁决、并发结算幂等及并发创建与来源关闭的互斥。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceHandoffConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceHandoffService handoffService;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM handoff_task_refs");
        jdbc.update("DELETE FROM handoff_settlements");
        jdbc.update("DELETE FROM resource_handoff_items");
        jdbc.update("DELETE FROM resource_handoffs");
        jdbc.update("DELETE FROM incident_delegates");
        jdbc.update("DELETE FROM incident_resources");
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
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void acquire(String incidentKey, String actor, String resourceKey) {
        handoffService.acquireResource(incidentKey, actor,
                new ResourceAcquireRequest(key(), resourceKey));
    }

    private HandoffCreateRequest handoffRequest(String handoffKey, String target,
                                                String receiver, String resourceKey) {
        IncidentView s = incidentService.get("INC-S");
        IncidentView t = incidentService.get(target);
        return new HandoffCreateRequest(handoffKey, target, receiver, s.version(), t.version(),
                T0, T0.plusSeconds(3600),
                List.of(new HandoffItemRequest(resourceKey, List.of())));
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

    private static long countApiErrors(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentCreateSameKey_replaysOnce() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        String handoffKey = "HO-" + UUID.randomUUID();
        HandoffCreateRequest request = handoffRequest(handoffKey, "INC-T", "bob", "RES-1");

        List<Object> results = runConcurrently(List.of(
                () -> handoffService.createHandoff("INC-S", "alice", request),
                () -> handoffService.createHandoff("INC-S", "alice", request)));

        long successes = results.stream().filter(HandoffView.class::isInstance).count();
        assertThat(successes).isEqualTo(2);
        HandoffView first = (HandoffView) results.get(0);
        HandoffView second = (HandoffView) results.get(1);
        assertThat(second).isEqualTo(first);
        Integer handoffCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs", Integer.class);
        assertThat(handoffCount).isEqualTo(1);
    }

    @Test
    void concurrentCreateDifferentKeys_sameResource_exactlyOneWins() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        HandoffCreateRequest req1 = handoffRequest("HO-" + UUID.randomUUID(), "INC-T", "bob",
                "RES-1");
        HandoffCreateRequest req2 = handoffRequest("HO-" + UUID.randomUUID(), "INC-T", "bob",
                "RES-1");

        List<Object> results = runConcurrently(List.of(
                () -> handoffService.createHandoff("INC-S", "alice", req1),
                () -> handoffService.createHandoff("INC-S", "alice", req2)));

        long successes = results.stream().filter(HandoffView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countApiErrors(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
        // 最终仅一条进行中交接，资源责任方唯一
        Integer handoffCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs", Integer.class);
        assertThat(handoffCount).isEqualTo(1);
        assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                .isEqualTo("INC-T");
    }

    @Test
    void concurrentTaskStartAndLeaseSettle_commitOrderAdjudicates() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        incidentService.createTask("INC-T", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "并发任务", List.of()));
        IncidentView s = incidentService.get("INC-S");
        IncidentView t = incidentService.get("INC-T");
        handoffService.createHandoff("INC-S", "alice", new HandoffCreateRequest(
                "HO-" + UUID.randomUUID(), "INC-T", "bob", s.version(), t.version(),
                T0, T0.plusSeconds(3600),
                List.of(new HandoffItemRequest("RES-1", List.of("T-1")))));
        // 租约到期
        ((ControllableClock) clock).setInstant(T0.plusSeconds(3600));

        // 并发：任务开始 vs 租约结算，按提交顺序裁决，两种终态均合法
        List<Object> results = runConcurrently(List.of(
                () -> incidentService.startTask("INC-T", "T-1", "bob", new TaskActionRequest(key())),
                () -> handoffService.settleExpired("INC-T", new HandoffSettleRequest(key()))));
        long failures = results.stream().filter(Exception.class::isInstance).count();
        assertThat(failures).isZero();

        HandoffView handoff = handoffService.listHandoffs("INC-S").handoffs().get(0);
        Integer refCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_task_refs", Integer.class);
        if (handoff.status().equals("SETTLED")) {
            // 结算先提交：未开始任务被解除资源并归还
            assertThat(refCount).isZero();
            assertThat(handoff.settlements()).hasSize(1);
            assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                    .isEqualTo("INC-S");
        } else {
            // 任务开始先提交：已开始任务继续持有资源，交接触发结束待结算
            assertThat(handoff.status()).isEqualTo("ACTIVE");
            assertThat(handoff.endReason()).isEqualTo("LEASE_EXPIRED");
            assertThat(refCount).isEqualTo(1);
            assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                    .isEqualTo("INC-T");
            // 任务终态后自动结算归还
            incidentService.completeTask("INC-T", "T-1", "bob", new TaskActionRequest(key()));
            HandoffView settled = handoffService.listHandoffs("INC-S").handoffs().get(0);
            assertThat(settled.status()).isEqualTo("SETTLED");
            assertThat(settled.settlements()).hasSize(1);
        }
    }

    @Test
    void concurrentSettle_singleSettlementWritten() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        HandoffCreateRequest request = handoffRequest("HO-" + UUID.randomUUID(), "INC-T", "bob",
                "RES-1");
        handoffService.createHandoff("INC-S", "alice", request);
        ((ControllableClock) clock).setInstant(T0.plusSeconds(3600));

        // 并发结算（不同 commandKey）：恰好一份结算记录
        List<Object> results = runConcurrently(List.of(
                () -> handoffService.settleExpired("INC-T", new HandoffSettleRequest(key())),
                () -> handoffService.settleExpired("INC-T", new HandoffSettleRequest(key()))));
        long successes = results.stream().filter(HandoffSettleView.class::isInstance).count();
        assertThat(successes).isEqualTo(2);
        long totalSettled = results.stream().filter(HandoffSettleView.class::isInstance)
                .map(HandoffSettleView.class::cast)
                .mapToLong(v -> v.settlements().size()).sum();
        assertThat(totalSettled).isEqualTo(1);
        Integer settlementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_settlements", Integer.class);
        assertThat(settlementCount).isEqualTo(1);
        assertThat(handoffService.listHandoffs("INC-S").handoffs().get(0).status())
                .isEqualTo("SETTLED");
    }

    @Test
    void concurrentCreateAndSourceClose_mutuallyExclusive() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "RESOLVED"));
        HandoffCreateRequest request = handoffRequest("HO-" + UUID.randomUUID(), "INC-T", "bob",
                "RES-1");

        // 并发：创建交接 vs 来源关闭，按提交顺序裁决，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> handoffService.createHandoff("INC-S", "alice", request),
                () -> incidentService.changeStatus("INC-S", "alice",
                        new StatusRequest(key(), "CLOSED"))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        assertThat(successes).isEqualTo(1);
        if (results.get(0) instanceof HandoffView) {
            // 交接先成交：来源关闭被借出资源阻断
            assertThat(countApiErrors(results, HttpStatus.CONFLICT)).isEqualTo(1);
            assertThat(incidentService.get("INC-S").status()).isEqualTo("RESOLVED");
            assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                    .isEqualTo("INC-T");
        } else {
            // 关闭先成交：交接因来源已关闭被拒绝，不留半成品
            assertThat(countApiErrors(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
            assertThat(incidentService.get("INC-S").status()).isEqualTo("CLOSED");
            Integer handoffCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM resource_handoffs", Integer.class);
            assertThat(handoffCount).isZero();
            assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                    .isEqualTo("INC-S");
        }
    }
}
