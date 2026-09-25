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

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAllocateRequest;
import com.example.starter.incident.dto.Requests.LeaseItem;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.LeaseAllocateView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源资质租约并发与幂等边界测试（真实 H2 内存库 + 真实并发线程）：
 * 同 leaseKey 并发单次生效重放、不同请求资源冲突按提交顺序裁决、
 * 撤销与开始/完成并发的提交顺序一致性、批量原子性。
 */
@SpringBootTest
class CredentialLeaseConcurrencyTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private CredentialLeaseService leaseService;

    @Autowired
    private JdbcTemplate jdbc;

    private Instant t0;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM credential_risks");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发资质", "reporter"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
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
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
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

    private LeaseItem item(String incident, String task, String resource, long startHours,
                           long endHours) {
        return new LeaseItem(incident, task, resource,
                t0.plus(startHours, ChronoUnit.HOURS), t0.plus(endHours, ChronoUnit.HOURS));
    }

    @Test
    void concurrentSameLeaseKey_singleAllocation_allReplay() throws Exception {
        commanding("INC-1", "alice");
        incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("C1")));
        leaseService.register(new CredentialRegisterRequest(key(), "R-1", "C1", null,
                t0.plus(10, ChronoUnit.HOURS)));
        String commandKey = key();
        List<Callable<LeaseAllocateView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> leaseService.allocate("alice",
                    new LeaseAllocateRequest(commandKey, List.of(item("INC-1", "T-1", "R-1", 1, 2)))));
        }
        List<Object> results = runConcurrently(tasks);

        List<LeaseAllocateView> views = results.stream()
                .filter(LeaseAllocateView.class::isInstance)
                .map(LeaseAllocateView.class::cast).toList();
        assertThat(views).hasSize(3);
        assertThat(views).allSatisfy(v -> assertThat(v).isEqualTo(views.get(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_leases", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentKeys_sameResourceOverlap_oneWins() throws Exception {
        commanding("INC-1", "alice");
        commanding("INC-2", "alice");
        incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("C1")));
        incidentService.createTask("INC-2", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "t", List.of(), List.of("C1")));
        leaseService.register(new CredentialRegisterRequest(key(), "R-1", "C1", null,
                t0.plus(10, ChronoUnit.HOURS)));

        List<Object> results = runConcurrently(List.of(
                () -> leaseService.allocate("alice", new LeaseAllocateRequest(key(),
                        List.of(item("INC-1", "T-1", "R-1", 1, 3)))),
                () -> leaseService.allocate("alice", new LeaseAllocateRequest(key(),
                        List.of(item("INC-2", "T-2", "R-1", 2, 4))))));

        long successes = results.stream().filter(LeaseAllocateView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_leases", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentRevokeAndStart_commitOrderConsistent() throws Exception {
        commanding("INC-1", "alice");
        incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("C1")));
        leaseService.register(new CredentialRegisterRequest(key(), "R-1", "C1", null,
                t0.plus(10, ChronoUnit.HOURS)));
        leaseService.allocate("alice", new LeaseAllocateRequest(key(),
                List.of(item("INC-1", "T-1", "R-1", 1, 3))));

        List<Object> results = runConcurrently(List.of(
                () -> incidentService.startTask("INC-1", "T-1", "alice",
                        new TaskActionRequest(key())),
                () -> leaseService.revoke("R-1", "C1", "alice",
                        new CredentialRevokeRequest(key(), "并发撤销"))));

        // 撤销必然最终生效；无论提交顺序，任务最终都为 CREDENTIAL_RISK 且恰有一条风险记录
        TaskView finalTask = incidentService.getTask("INC-1", "T-1");
        assertThat(finalTask.status()).isEqualTo("CREDENTIAL_RISK");
        assertThat(leaseService.listRisks("INC-1").risks()).hasSize(1);
        Object startResult = results.get(0);
        if (startResult instanceof ApiException e) {
            // 撤销先提交：开始被资质门禁拒绝
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        } else {
            // 开始先提交：开始成功，随后撤销将其转入风险
            assertThat(startResult).isInstanceOf(TaskView.class);
            assertThat(((TaskView) startResult).status()).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    void concurrentRevokeAndComplete_commitOrderConsistent() throws Exception {
        commanding("INC-1", "alice");
        incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("C1")));
        leaseService.register(new CredentialRegisterRequest(key(), "R-1", "C1", null,
                t0.plus(10, ChronoUnit.HOURS)));
        leaseService.allocate("alice", new LeaseAllocateRequest(key(),
                List.of(item("INC-1", "T-1", "R-1", 1, 3))));
        incidentService.startTask("INC-1", "T-1", "alice", new TaskActionRequest(key()));

        List<Object> results = runConcurrently(List.of(
                () -> incidentService.completeTask("INC-1", "T-1", "alice",
                        new TaskActionRequest(key())),
                () -> leaseService.revoke("R-1", "C1", "alice",
                        new CredentialRevokeRequest(key(), "并发撤销"))));

        TaskView finalTask = incidentService.getTask("INC-1", "T-1");
        Object completeResult = results.get(0);
        if ("DONE".equals(finalTask.status())) {
            // 完成先提交：已完成任务不被撤销改写，无风险记录
            assertThat(completeResult).isInstanceOf(TaskView.class);
            assertThat(leaseService.listRisks("INC-1").risks()).isEmpty();
        } else {
            // 撤销先提交：风险门禁拦截完成
            assertThat(finalTask.status()).isEqualTo("CREDENTIAL_RISK");
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(leaseService.listRisks("INC-1").risks()).hasSize(1);
        }
    }

    @Test
    void batchAllocate_partialFailure_commitOrderLeavesNothing() throws Exception {
        commanding("INC-1", "alice");
        commanding("INC-2", "alice");
        incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("C1")));
        incidentService.createTask("INC-2", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "t", List.of(), List.of("C1")));
        leaseService.register(new CredentialRegisterRequest(key(), "R-1", "C1", null,
                t0.plus(10, ChronoUnit.HOURS)));

        // 并发两个批量请求竞争同一资源的同一时段：最多一个批量整体成功，另一个整体回滚
        List<Object> results = runConcurrently(List.of(
                () -> leaseService.allocate("alice", new LeaseAllocateRequest(key(),
                        List.of(item("INC-1", "T-1", "R-1", 1, 2),
                                item("INC-2", "T-2", "R-1", 2, 3)))),
                () -> leaseService.allocate("alice", new LeaseAllocateRequest(key(),
                        List.of(item("INC-1", "T-1", "R-1", 1, 2))))));

        long successes = results.stream().filter(LeaseAllocateView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        LeaseAllocateView winner = results.stream()
                .filter(LeaseAllocateView.class::isInstance)
                .map(LeaseAllocateView.class::cast).findFirst().orElseThrow();
        // 成功的批量要么创建 2 条（含 T-1 1-2 与 T-2 2-3），要么创建 1 条；数据库无半单残留
        assertThat(winner.created()).hasSize((int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class));
    }
}
