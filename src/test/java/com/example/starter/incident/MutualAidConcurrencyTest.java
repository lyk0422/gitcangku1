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

import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffItemRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskAssignResourceRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.HandoffBatchView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨事件互助交接并发边界测试（真实 H2 MySQL 兼容库、真实并发线程）：
 * 同 handoffKey 并发单次落库；同一空闲资源并发交接按提交顺序仅一个成功且不产生重复 ACTIVE；
 * 已开始任务终态归还与租约到期结算并发时结算唯一、最终资源归还来源。
 * 所有用例以 CountDownLatch 协调真实并发并设置超时，断言最终数据而非打印。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class MutualAidConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControllableClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM handoff_settlements");
        jdbc.update("DELETE FROM resource_handoffs");
        jdbc.update("DELETE FROM incident_receiving_delegates");
        jdbc.update("DELETE FROM incident_resources");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        clock.setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void twoCommandingIncidentsAndResource(String resourceKey) {
        service.report(new ReportRequest("INC-A", "S2", "互助并发", "reporter-1"));
        service.takeover("INC-A", "alice", new TakeoverRequest(key()));
        service.report(new ReportRequest("INC-B", "S2", "互助并发", "reporter-2"));
        service.takeover("INC-B", "bob", new TakeoverRequest(key()));
        service.registerResource("INC-A", "alice",
                new ResourceRegisterRequest(key(), resourceKey, "资源"));
    }

    private static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
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
    void concurrentSameHandoffKey_singleRow() throws Exception {
        twoCommandingIncidentsAndResource("RES-1");
        var request = new HandoffCreateRequest("INC-B", "bob", List.of(new HandoffItemRequest(
                "HK-C1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))));
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.createHandoffs("INC-A", "alice", request));
        }
        List<Object> results = runConcurrently(tasks);

        long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs WHERE handoff_key = 'HK-C1'",
                Long.class);
        assertThat(rows).isEqualTo(1);
        // 每个请求要么成功（重放/首次），要么 409；不允许出现 5xx 半成品
        for (Object r : results) {
            if (r instanceof ApiException e) {
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            } else {
                assertThat(r).isInstanceOf(HandoffBatchView.class);
            }
        }
        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("TARGET");
    }

    @Test
    void concurrentSameResourceDifferentKeys_onlyOneWins() throws Exception {
        twoCommandingIncidentsAndResource("RES-1");
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            String hk = "HK-R" + i;
            tasks.add(() -> service.createHandoffs("INC-A", "alice",
                    new HandoffCreateRequest("INC-B", "bob", List.of(new HandoffItemRequest(
                            hk, "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))))));
        }
        List<Object> results = runConcurrently(tasks);

        long success = results.stream().filter(HandoffBatchView.class::isInstance).count();
        long rejected = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        // 最终仅一条 ACTIVE 交接，资源 LEASED_OUT
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs WHERE resource_id ="
                        + " (SELECT id FROM incident_resources WHERE resource_key='RES-1')"
                        + " AND status='ACTIVE'", Long.class);
        assertThat(active).isEqualTo(1);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM resource_handoffs", Long.class);
        assertThat(total).isEqualTo(1);
    }

    @Test
    void concurrentTaskFinishAndLeaseSettle_settlementStaysUniqueAndReturned() throws Exception {
        twoCommandingIncidentsAndResource("RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(new HandoffItemRequest("HK-S", "RES-1", T0,
                        T0.plus(1, ChronoUnit.HOURS)))));
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "处置", List.of()));
        service.assignResourceToTask("INC-B", "T-1", "bob",
                new TaskAssignResourceRequest(key(), "HK-S"));
        service.startTask("INC-B", "T-1", "bob", new TaskStartRequest(key()));
        clock.setInstant(T0.plus(2, ChronoUnit.HOURS));

        // 并发：已开始任务完成（TASK_DONE 归还）与租约到期结算
        List<Callable<?>> tasks = List.of(
                () -> service.completeTask("INC-B", "T-1", "bob", new TaskActionRequest(key())),
                () -> service.settleExpiredLeases("INC-B", new HandoffSettleRequest(key())));
        List<Object> results = runConcurrently(tasks);

        // 不允许出现未预期异常
        for (Object r : results) {
            assertThat(r).isNotInstanceOf(ApiException.class);
        }
        // 最终资源归还来源，交接 SETTLED，不可变结算恰好一条
        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("SOURCE");
        String status = jdbc.queryForObject(
                "SELECT status FROM resource_handoffs WHERE handoff_key='HK-S'", String.class);
        assertThat(status).isEqualTo("SETTLED");
        // 不可变结算恰好一条（任务完成与到期结算并发裁决后不重复）
        Long settlements = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_settlements", Long.class);
        assertThat(settlements).isEqualTo(1);
    }
}
