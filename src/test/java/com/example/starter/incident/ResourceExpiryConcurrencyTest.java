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

import com.example.starter.incident.dto.Requests.RenewItem;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRenewRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.ResourceLeaseView;
import com.example.starter.incident.dto.Responses.ResourceRenewalView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源授权并发边界测试：同一资源并发分配仅一个生效、同一租约并发续期不产生
 * 重叠授权（版本只推进一次）、同 commandKey 并发单次生效、并发完成与到期
 * 检查按提交顺序形成合法终态。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceExpiryConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_resource_events");
        jdbc.update("DELETE FROM incident_task_leases");
        jdbc.update("DELETE FROM resource_locks");
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

    private void openTask(String incidentKey, String commander, String taskKey) {
        incidentService.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "G", "t", List.of()));
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
    void concurrentAssign_sameResource_singleWinner() throws Exception {
        commanding("INC-CA", "alice");
        commanding("INC-CB", "bob");
        openTask("INC-CA", "alice", "T-1");
        openTask("INC-CB", "bob", "T-1");

        // 两个事件并发分配同一资源：恰好一个成功，另一个 409
        List<Object> results = runConcurrently(List.of(
                () -> service.assign("INC-CA", "T-1", "alice",
                        new ResourceAssignRequest(key(), "res-x", 1, T0.plusSeconds(600))),
                () -> service.assign("INC-CB", "T-1", "bob",
                        new ResourceAssignRequest(key(), "res-x", 1, T0.plusSeconds(600)))));

        long successes = results.stream().filter(ResourceLeaseView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        // 最终只有一条生效占用，不产生重叠授权
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_leases WHERE resource_key = 'res-x'"
                        + " AND status = 'ACTIVE'", Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void concurrentRenew_sameLease_noOverlappingAuthorization() throws Exception {
        commanding("INC-CR", "alice");
        openTask("INC-CR", "alice", "T-1");
        service.assign("INC-CR", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-y", 1, T0.plusSeconds(300)));

        // 两个不同授权角色并发续期同一租约（不同新版本）：恰好一个生效
        List<Object> results = runConcurrently(List.of(
                () -> service.renew("INC-CR", "bob",
                        new ResourceRenewRequest(key(), List.of(
                                new RenewItem("T-1", 1, 2, T0.plusSeconds(900))))),
                () -> service.renew("INC-CR", "carol",
                        new ResourceRenewRequest(key(), List.of(
                                new RenewItem("T-1", 1, 3, T0.plusSeconds(1200)))))));

        long successes = results.stream().filter(ResourceRenewalView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        // 失败方为 422（期望版本已被先提交者推进）
        long unprocessable = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        assertThat(unprocessable).isEqualTo(1);
        // 版本只推进一次，RENEW 证据只有一条，不产生重叠授权
        ResourceLeaseView lease = service.getLease("INC-CR", "T-1");
        Integer renewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resource_events WHERE event_type = 'RENEW'",
                Integer.class);
        assertThat(renewEvents).isEqualTo(1);
        Object winner = results.stream().filter(ResourceRenewalView.class::isInstance).findFirst()
                .orElseThrow();
        int expectedVersion = ((ResourceRenewalView) winner).items().get(0).newVersion();
        assertThat(lease.authVersion()).isEqualTo(expectedVersion);
    }

    @Test
    void concurrentAssign_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-CK", "alice");
        openTask("INC-CK", "alice", "T-1");
        String commandKey = key();
        List<Callable<ResourceLeaseView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> service.assign("INC-CK", "T-1", "alice",
                    new ResourceAssignRequest(commandKey, "res-z", 1, T0.plusSeconds(600))));
        }
        List<Object> results = runConcurrently(calls);

        List<ResourceLeaseView> successes = results.stream()
                .filter(ResourceLeaseView.class::isInstance)
                .map(ResourceLeaseView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_leases", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey)).isEqualTo(1);
    }

    @Test
    void concurrentCompleteAndExpiryCheck_commitOrderWins() throws Exception {
        commanding("INC-CE", "alice");
        openTask("INC-CE", "alice", "T-1");
        service.assign("INC-CE", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-w", 1, T0.plusSeconds(60)));
        // 时钟已到到期点：并发完成与到期检查，按事件行锁串行，先提交者裁决
        ((ControllableClock) clock).setInstant(T0.plusSeconds(60));
        List<Object> results = runConcurrently(List.of(
                () -> incidentService.completeTask("INC-CE", "T-1", "alice",
                        new TaskActionRequest(key())),
                () -> service.checkAuthorization("INC-CE", "T-1",
                        new TaskActionRequest(key()))));

        // 两种合法终态：检查先提交（任务 AUTHORIZATION_EXPIRED，完成被 422 拦截）
        // 或完成先评估（同样触发到期门禁 422，检查随后看到已固化状态）
        TaskView task = incidentService.getTask("INC-CE", "T-1");
        assertThat(task.status()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(service.getLease("INC-CE", "T-1").status()).isEqualTo("EXPIRED");
        // 完成必然被到期门禁拦截（422）
        assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 到期证据至多一条（检查与门禁不会重复固化）
        Integer expireEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resource_events WHERE event_type = 'EXPIRE'",
                Integer.class);
        assertThat(expireEvents).isEqualTo(1);
    }
}
