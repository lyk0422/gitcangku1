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

import com.example.starter.incident.dto.Requests.LeaseRenewRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源租约并发边界测试：验证同一资源并发分配不产生重叠授权、并发续期按提交
 * 顺序裁决（同新版本恰一个成功）、并发完成到期任务单次物化、同 commandKey
 * 并发单次生效。使用可控 Clock 与闩锁协调真实并发，不设长时间休眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceLeaseConcurrencyTest {

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
        jdbc.update("DELETE FROM resource_lease_events");
        jdbc.update("DELETE FROM incident_task_executions");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM leased_resources");
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
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "DB", "任务", List.of()));
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

    private static long countStatus(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentAssignSameResource_singleActiveLease() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "bob", "T-1");

        List<Object> results = runConcurrently(List.of(
                () -> service.assignResource("INC-A", "T-1", "alice",
                        new ResourceAssignRequest(key(), "RES-X", "v1", T0.plusSeconds(600))),
                () -> service.assignResource("INC-B", "T-1", "bob",
                        new ResourceAssignRequest(key(), "RES-X", "v1", T0.plusSeconds(600)))));

        // 恰好一个成功，另一个 409（资源被占用）；全局至多一条活动租约
        assertThat(results.stream().filter(TaskView.class::isInstance).count()).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM leased_resources WHERE resource_key = 'RES-X'"
                        + " AND active_lease_id IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentRenewSameNewVersion_exactlyOneWins() throws Exception {
        commanding("INC-R", "alice");
        createTask("INC-R", "alice", "T-1");
        service.assignResource("INC-R", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-R", "v1", T0.plusSeconds(600)));

        // 并发续期提交相同新版本：按提交顺序裁决，恰一个成功，另一个 422（版本已存在）
        List<Object> results = runConcurrently(List.of(
                () -> service.renewLeases("INC-R", "authorizer-1",
                        new LeaseRenewRequest(key(), List.of("T-1"), "v2", "OPS_LEAD",
                                T0.plusSeconds(3600))),
                () -> service.renewLeases("INC-R", "authorizer-2",
                        new LeaseRenewRequest(key(), List.of("T-1"), "v2", "OPS_LEAD",
                                T0.plusSeconds(3600)))));

        assertThat(results.stream().filter(IncidentTasksView.class::isInstance).count())
                .isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
        // 不产生重叠授权：恰好一个 ACTIVE 租约（版本 2），一个 SUPERSEDED
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT lease_version FROM resource_leases WHERE status = 'ACTIVE'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'SUPERSEDED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentCompleteOnExpired_singleMaterialization() throws Exception {
        commanding("INC-E", "alice");
        createTask("INC-E", "alice", "T-1");
        service.assignResource("INC-E", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-E", "v1", T0.plusSeconds(30)));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(31));

        // 并发完成已到期任务：按提交顺序，恰一个拦截（200 + AUTHORIZATION_EXPIRED），
        // 另一个面对终态 409
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-E", "T-1", "alice", new TaskActionRequest(key())),
                () -> service.completeTask("INC-E", "T-1", "alice", new TaskActionRequest(key()))));

        long intercepted = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast)
                .filter(v -> v.status().equals("AUTHORIZATION_EXPIRED")).count();
        assertThat(intercepted).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        // 到期物化单次生效：EXPIRE 与 EXECUTION_BLOCKED 证据各一条，任务终态一致
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lease_events WHERE event_type = 'EXPIRE'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lease_events"
                        + " WHERE event_type = 'EXECUTION_BLOCKED'", Integer.class)).isEqualTo(1);
        assertThat(service.getTask("INC-E", "T-1").status()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'EXPIRED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentAssign_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-K", "alice");
        createTask("INC-K", "alice", "T-1");
        String commandKey = key();
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.assignResource("INC-K", "T-1", "alice",
                    new ResourceAssignRequest(commandKey, "RES-K", "v1", T0.plusSeconds(600))));
        }
        List<Object> results = runConcurrently(tasks);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey)).isEqualTo(1);
    }
}
