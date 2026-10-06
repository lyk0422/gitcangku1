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

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRenewItem;
import com.example.starter.incident.dto.Requests.ResourceRenewRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.ResourceAssignmentView;
import com.example.starter.incident.dto.Responses.ResourceRenewView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源租约并发边界测试：同一资源并发分配只有一个持有方、并发续期不产生
 * 重叠授权、完成与续期按事务提交顺序裁决、同 commandKey 并发单次生效。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceAssignmentConcurrencyTest {

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
        jdbc.update("DELETE FROM resource_assignment_events");
        jdbc.update("DELETE FROM task_execution_logs");
        jdbc.update("DELETE FROM task_resource_assignments");
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
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    /**
     * 并发提交一批操作并收集结果（成功值或异常）。
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
    void concurrentAssign_sameResource_singleHolder() throws Exception {
        commanding("INC-C-A", "alice");
        commanding("INC-C-B", "bob");
        createTask("INC-C-A", "alice", "T-1");
        createTask("INC-C-B", "bob", "T-1");

        List<Object> results = runConcurrently(List.of(
                () -> service.assignResource("INC-C-A", "T-1", "alice",
                        new ResourceAssignRequest(key(), "RES-X", 1, T0.plusSeconds(600))),
                () -> service.assignResource("INC-C-B", "T-1", "bob",
                        new ResourceAssignRequest(key(), "RES-X", 1, T0.plusSeconds(600)))));

        long successes = results.stream().filter(ResourceAssignmentView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        // 恰好一条 ACTIVE 租约，且注册表持有方指向它
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_resource_assignments WHERE resource_key = 'RES-X'"
                        + " AND status = 'ACTIVE'", Integer.class);
        assertThat(active).isEqualTo(1);
        var row = jdbc.queryForMap(
                "SELECT r.holder_assignment_id, a.id AS aid FROM incident_resources r"
                        + " LEFT JOIN task_resource_assignments a ON a.resource_key = r.resource_key"
                        + " AND a.status = 'ACTIVE' WHERE r.resource_key = 'RES-X'");
        assertThat(((Number) row.get("holder_assignment_id")).longValue())
                .isEqualTo(((Number) row.get("aid")).longValue());
    }

    @Test
    void concurrentRenew_sameResource_noOverlappingAuthorization() throws Exception {
        commanding("INC-C-R", "alice");
        createTask("INC-C-R", "alice", "T-1");
        service.assignResource("INC-C-R", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-R", 1, T0.plusSeconds(600)));

        // 两个不同授权角色并发把版本 1 续到 2：恰好一个成功，不产生重叠授权
        List<Object> results = runConcurrently(List.of(
                () -> service.renewResources("INC-C-R", "officer-1",
                        new ResourceRenewRequest(key(), List.of(
                                new ResourceRenewItem("T-1", 2, T0.plusSeconds(3600))))),
                () -> service.renewResources("INC-C-R", "officer-2",
                        new ResourceRenewRequest(key(), List.of(
                                new ResourceRenewItem("T-1", 2, T0.plusSeconds(3600)))))));

        long successes = results.stream().filter(ResourceRenewView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
        // 最终版本恰好推进一次，RENEW 证据只有一条
        assertThat(service.getTaskResource("INC-C-R", "T-1").authorizationVersion()).isEqualTo(2);
        Integer renewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_assignment_events WHERE event_type = 'RENEW'",
                Integer.class);
        assertThat(renewEvents).isEqualTo(1);
    }

    @Test
    void concurrentCompleteAndRenew_commitOrderConsistent() throws Exception {
        commanding("INC-C-M", "alice");
        createTask("INC-C-M", "alice", "T-1");
        service.assignResource("INC-C-M", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-M", 1, T0.plusSeconds(600)));

        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-C-M", "T-1", "alice",
                        new TaskActionRequest(key())),
                () -> service.renewResources("INC-C-M", "officer",
                        new ResourceRenewRequest(key(), List.of(
                                new ResourceRenewItem("T-1", 2, T0.plusSeconds(3600)))))));

        TaskView task = service.getTask("INC-C-M", "T-1");
        Object completeResult = results.get(0);
        Object renewResult = results.get(1);
        // 两种提交顺序下完成都成功（续期先提交则完成校验的是新版本租约）
        assertThat(completeResult).isInstanceOf(TaskView.class);
        assertThat(task.status()).isEqualTo("DONE");
        // 租约最终都被回收
        Integer released = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_resource_assignments WHERE status = 'RELEASED'",
                Integer.class);
        assertThat(released).isEqualTo(1);
        if (renewResult instanceof ResourceRenewView) {
            // 续期先提交：版本推进到 2 后被完成回收
            Integer version = jdbc.queryForObject(
                    "SELECT authorization_version FROM task_resource_assignments",
                    Integer.class);
            assertThat(version).isEqualTo(2);
        } else {
            // 完成先提交：租约已回收，续期 422（NO_ACTIVE_ASSIGNMENT）
            assertThat(renewResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
        // 无论哪种顺序：证据序列完整且以 RELEASE 收尾
        List<String> eventTypes = jdbc.queryForList(
                "SELECT event_type FROM resource_assignment_events ORDER BY id", String.class);
        assertThat(eventTypes.get(0)).isEqualTo("ASSIGN");
        assertThat(eventTypes.get(eventTypes.size() - 1)).isEqualTo("RELEASE");
    }

    @Test
    void concurrentAssign_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-C-K", "alice");
        createTask("INC-C-K", "alice", "T-1");
        String commandKey = key();
        List<Callable<ResourceAssignmentView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> service.assignResource("INC-C-K", "T-1", "alice",
                    new ResourceAssignRequest(commandKey, "RES-K", 1, T0.plusSeconds(600))));
        }
        List<Object> results = runConcurrently(calls);

        List<ResourceAssignmentView> successes = results.stream()
                .filter(ResourceAssignmentView.class::isInstance)
                .map(ResourceAssignmentView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer assignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_resource_assignments WHERE resource_key = 'RES-K'",
                Integer.class);
        assertThat(assignments).isEqualTo(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }
}
