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

import com.example.starter.incident.dto.Requests.RenewalEntry;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskRenewalRequest;
import com.example.starter.incident.dto.Responses.RenewalView;
import com.example.starter.incident.dto.Responses.ResourceAssignmentView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源授权并发边界测试：验证同一资源并发分配/续期按事务提交顺序裁决、
 * 不产生重叠授权，同 commandKey 并发单次生效，完成与续期并发时结果一致。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceAuthorizationConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceAuthorizationService resourceService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_authorization_events");
        jdbc.update("DELETE FROM task_execution_logs");
        jdbc.update("DELETE FROM task_resource_assignments");
        jdbc.update("DELETE FROM resource_locks");
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
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "DB", "处置", List.of()));
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
    void concurrentAssign_sameResource_singleOccupant() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "bob", "T-2");
        // 并发分配同一资源：资源串行锁下恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> resourceService.assign("INC-A", "T-1", "alice",
                        new ResourceAssignRequest(key(), "RES-1", 1L, T0.plusSeconds(600))),
                () -> resourceService.assign("INC-B", "T-2", "bob",
                        new ResourceAssignRequest(key(), "RES-1", 1L, T0.plusSeconds(600)))));

        long successes = results.stream().filter(ResourceAssignmentView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        Integer assignments = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_resource_assignments WHERE resource_key = 'RES-1'",
                Integer.class);
        assertThat(assignments).isEqualTo(1);
    }

    @Test
    void concurrentRenew_sameResource_noOverlap() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "bob", "T-2");
        // A 的租约先到期，B 短暂占用后也到期：两个事件对同一资源并发续期
        resourceService.assign("INC-A", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1L, T0.plusSeconds(300)));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        resourceService.assign("INC-B", "T-2", "bob",
                new ResourceAssignRequest(key(), "RES-1", 1L, T0.plusSeconds(450)));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(500));

        List<Object> results = runConcurrently(List.of(
                () -> resourceService.renew("INC-A", "carol",
                        new TaskRenewalRequest(key(),
                                List.of(new RenewalEntry("T-1", 2L, T0.plusSeconds(900))))),
                () -> resourceService.renew("INC-B", "dave",
                        new TaskRenewalRequest(key(),
                                List.of(new RenewalEntry("T-2", 2L, T0.plusSeconds(900)))))));

        // 恰好一个续期成功：同一资源不产生重叠授权
        long successes = results.stream().filter(RenewalView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_resource_assignments a"
                        + " JOIN incident_tasks t ON t.id = a.task_id"
                        + " WHERE a.resource_key = 'RES-1' AND a.expires_at > ?"
                        + " AND t.status NOT IN ('DONE','CANCELLED')",
                Integer.class, java.sql.Timestamp.from(T0.plusSeconds(500)));
        assertThat(active).isEqualTo(1);
        Integer renewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_authorization_events WHERE event_type = 'RENEW'",
                Integer.class);
        assertThat(renewEvents).isEqualTo(1);
    }

    @Test
    void concurrentRenew_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        resourceService.assign("INC-A", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1L, T0.plusSeconds(300)));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        String commandKey = key();
        List<Callable<RenewalView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> resourceService.renew("INC-A", "carol",
                    new TaskRenewalRequest(commandKey,
                            List.of(new RenewalEntry("T-1", 2L, T0.plusSeconds(900))))));
        }
        List<Object> results = runConcurrently(tasks);

        List<RenewalView> successes = results.stream().filter(RenewalView.class::isInstance)
                .map(RenewalView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        // 版本只推进一次，RENEW 证据只有一条
        Long version = jdbc.queryForObject(
                "SELECT authorization_version FROM task_resource_assignments a"
                        + " JOIN incident_tasks t ON t.id = a.task_id WHERE t.task_key = 'T-1'",
                Long.class);
        assertThat(version).isEqualTo(2L);
        Integer renewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_authorization_events WHERE event_type = 'RENEW'",
                Integer.class);
        assertThat(renewEvents).isEqualTo(1);
    }

    @Test
    void concurrentCompleteAndRenew_commitOrderConsistent() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        resourceService.assign("INC-A", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1L, T0.plusSeconds(300)));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));

        // 并发：完成（到期将被拦截）与续期（不同授权角色）
        List<Object> results = runConcurrently(List.of(
                () -> incidentService.completeTask("INC-A", "T-1", "alice",
                        new TaskActionRequest(key())),
                () -> resourceService.renew("INC-A", "carol",
                        new TaskRenewalRequest(key(),
                                List.of(new RenewalEntry("T-1", 2L, T0.plusSeconds(900)))))));

        // 续期必然成功；完成是否成功取决于其评估时租约是否已续期
        RenewalView renewal = (RenewalView) results.stream()
                .filter(RenewalView.class::isInstance).findFirst().orElseThrow();
        assertThat(renewal.renewed().get(0).newAuthorizationVersion()).isEqualTo(2L);
        TaskView task = incidentService.getTask("INC-A", "T-1");
        Object completeResult = results.get(0);
        if (task.status().equals("DONE")) {
            // 续期先提交：完成时授权有效
            assertThat(completeResult).isInstanceOf(TaskView.class);
        } else {
            // 完成先评估：到期被拦截 409，续期后恢复到期前状态
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(task.authorizationVersion()).isEqualTo(2L);
        }
    }
}
