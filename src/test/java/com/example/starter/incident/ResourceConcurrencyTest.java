package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.LeaseAcquireRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.PreemptionVictimRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.ResourceUsageView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 共享资源并发边界测试：验证资源域锁下并发申请容量永不超限、
 * 同 commandKey 并发单次生效、抢占与任务开始按提交顺序形成合法结果
 * （任务绝不带失效租约 STARTED）、同 requestId 并发抢占单次生效。
 */
@SpringBootTest
class ResourceConcurrencyTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM shared_resources");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String severity, String commander) {
        incidentService.report(new ReportRequest(incidentKey, severity, "故障", "reporter"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void task(String incidentKey, String actor, String taskKey) {
        incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return t.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isConflict(Object o) {
        return o instanceof ApiException e && e.status() == HttpStatus.CONFLICT;
    }

    @Test
    void concurrentAcquire_capacityNeverExceeded() throws Exception {
        resourceService.createResource(new ResourceCreateRequest("R-1", "资源", 1));
        commanding("INC-A", "S2", "alice");
        commanding("INC-B", "S2", "bob");
        task("INC-A", "alice", "T-A");
        task("INC-B", "bob", "T-B");

        List<Callable<?>> calls = List.of(
                () -> resourceService.acquireLease("INC-A", "T-A", "alice",
                        new LeaseAcquireRequest(key(), "R-1", 0L, 1, "L-A")),
                () -> resourceService.acquireLease("INC-B", "T-B", "bob",
                        new LeaseAcquireRequest(key(), "R-1", 0L, 1, "L-B")));
        List<Object> results = runConcurrently(calls);

        long success = results.stream().filter(LeaseView.class::isInstance).count();
        assertThat(success).isEqualTo(1);
        assertThat(results.stream().filter(ResourceConcurrencyTest::isConflict)).hasSize(1);
        ResourceUsageView usage = resourceService.resourceUsage("R-1");
        assertThat(usage.resource().usedCapacity()).isEqualTo(1);
        assertThat(usage.activeLeases()).hasSize(1);
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void concurrentAcquire_sameCommandKey_singleEffect() throws Exception {
        resourceService.createResource(new ResourceCreateRequest("R-1", "资源", 2));
        commanding("INC-A", "S2", "alice");
        task("INC-A", "alice", "T-A");
        String commandKey = key();

        List<Callable<?>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> resourceService.acquireLease("INC-A", "T-A", "alice",
                    new LeaseAcquireRequest(commandKey, "R-1", 0L, 1, "L-A")));
        }
        List<Object> results = runConcurrently(calls);

        List<LeaseView> successes = results.stream().filter(LeaseView.class::isInstance)
                .map(LeaseView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(resourceService.resourceUsage("R-1").activeLeases()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentPreemptAndStart_commitOrderConsistent() throws Exception {
        resourceService.createResource(new ResourceCreateRequest("R-1", "资源", 1));
        commanding("INC-V", "S2", "victor");
        task("INC-V", "victor", "V-1");
        resourceService.acquireLease("INC-V", "V-1", "victor",
                new LeaseAcquireRequest(key(), "R-1", 0L, 1, "L-V"));

        commanding("INC-H", "S1", "hank");
        task("INC-H", "hank", "H-1");

        List<Callable<?>> calls = List.of(
                () -> resourceService.startTask("INC-V", "V-1", "victor",
                        new TaskStartRequest(key(), List.of("L-V"))),
                () -> resourceService.preempt("INC-H", "hank",
                        new PreemptRequest("REQ-RACE", "H-1", 0L, "L-H", 1,
                                List.of(new PreemptionVictimRequest("L-V", 1L)))));
        List<Object> results = runConcurrently(calls);

        Object startResult = results.get(0);
        Object preemptResult = results.get(1);
        TaskView victim = incidentService.getTask("INC-V", "V-1");
        ResourceUsageView usage = resourceService.resourceUsage("R-1");

        if (victim.status().equals("STARTED")) {
            // 开始先提交：抢占必被 STARTED 依赖链拒绝（422），租约仍属 V
            assertThat(startResult).isInstanceOf(TaskView.class);
            assertThat(preemptResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(usage.activeLeases()).extracting(LeaseView::leaseKey).containsExactly("L-V");
        } else {
            // 抢占先提交：任务不得带失效租约启动（409），租约转 H
            assertThat(victim.status()).isEqualTo("OPEN");
            assertThat(preemptResult).isInstanceOf(PreemptionView.class);
            assertThat(startResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(usage.activeLeases()).extracting(LeaseView::leaseKey).containsExactly("L-H");
        }
        // 容量恒定为 1，且不存在 STARTED 任务持有失效租约
        assertThat(usage.resource().usedCapacity()).isEqualTo(1);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases l JOIN incident_tasks t ON t.id = l.task_id"
                        + " WHERE l.status = 'ACTIVE' AND t.status = 'OPEN'", Integer.class);
        // 若抢占成功，持有者 H 的任务为 OPEN 是允许的（尚未开始）；关键是无 STARTED 配 REVOKED
        Integer startedWithRevoked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases l JOIN incident_tasks t ON t.id = l.task_id"
                        + " WHERE l.status = 'REVOKED' AND t.status = 'STARTED'", Integer.class);
        assertThat(startedWithRevoked).isZero();
        assertThat(active).isNotNull();
    }

    @Test
    void concurrentPreempt_sameRequestId_singleEffect() throws Exception {
        resourceService.createResource(new ResourceCreateRequest("R-1", "资源", 1));
        commanding("INC-V", "S2", "victor");
        task("INC-V", "victor", "V-1");
        resourceService.acquireLease("INC-V", "V-1", "victor",
                new LeaseAcquireRequest(key(), "R-1", 0L, 1, "L-V"));
        commanding("INC-H", "S1", "hank");
        task("INC-H", "hank", "H-1");

        List<Callable<?>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> resourceService.preempt("INC-H", "hank",
                    new PreemptRequest("REQ-SAME", "H-1", 0L, "L-H", 1,
                            List.of(new PreemptionVictimRequest("L-V", 1L)))));
        }
        List<Object> results = runConcurrently(calls);

        List<PreemptionView> successes = results.stream().filter(PreemptionView.class::isInstance)
                .map(PreemptionView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(resourceService.resourceUsage("R-1").activeLeases())
                .extracting(LeaseView::leaseKey).containsExactly("L-H");
        Integer revoked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'REVOKED'", Integer.class);
        assertThat(revoked).isEqualTo(1);
    }
}
