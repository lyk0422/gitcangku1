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

import com.example.starter.incident.dto.Requests.LeaseRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.VictimRef;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 共享资源并发边界测试：验证资源池全局锁下并发租约申请容量永不超限、
 * 同 requestId 并发单次生效、抢占与任务启动/完成/依赖变化按提交顺序形成合法结果、
 * 并发抢占至多一单成功。
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
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM shared_resources");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String severity, String commander) {
        incidentService.report(new ReportRequest(incidentKey, severity, "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey,
                                List<String> blockers) {
        return incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "t", blockers));
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

    private static long countConflicts(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
    }

    @Test
    void concurrentLeaseRequests_capacityNeverExceeded() throws Exception {
        resourceService.createResource("ops", new ResourceCreateRequest("RES-C", 2));
        List<Callable<LeaseView>> calls = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String incidentKey = "INC-C" + i;
            String actor = "actor-" + i;
            commanding(incidentKey, "S2", actor);
            TaskView task = createTask(incidentKey, actor, "T-1", List.of());
            String leaseKey = "LK-" + i;
            calls.add(() -> resourceService.requestLease(incidentKey, "T-1", actor,
                    new LeaseRequest(key(), "RES-C", leaseKey, 1, task.version())));
        }
        List<Object> results = runConcurrently(calls);

        long successes = results.stream().filter(LeaseView.class::isInstance).count();
        assertThat(successes).isEqualTo(2);
        assertThat(countConflicts(results)).isEqualTo(3);
        // 容量永不超限
        assertThat(resourceService.getResource("RES-C").activeUnits()).isEqualTo(2);
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class);
        assertThat(activeCount).isEqualTo(2);
    }

    @Test
    void concurrentLeaseRequest_sameRequestId_singleEffect() throws Exception {
        commanding("INC-S", "S2", "alice");
        TaskView task = createTask("INC-S", "alice", "T-1", List.of());
        resourceService.createResource("ops", new ResourceCreateRequest("RES-S", 5));
        String requestId = key();
        List<Callable<LeaseView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> resourceService.requestLease("INC-S", "T-1", "alice",
                    new LeaseRequest(requestId, "RES-S", "LK-1", 1, task.version())));
        }
        List<Object> results = runConcurrently(calls);

        List<LeaseView> successes = results.stream().filter(LeaseView.class::isInstance)
                .map(LeaseView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(resourceService.listLeases("RES-S").leases()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentPreemptAndStart_commitOrderWins() throws Exception {
        commanding("INC-L", "S3", "bob");
        commanding("INC-H", "S1", "carol");
        TaskView tl = createTask("INC-L", "bob", "TL", List.of());
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        resourceService.createResource("ops", new ResourceCreateRequest("RES-P", 1));
        resourceService.requestLease("INC-L", "TL", "bob",
                new LeaseRequest(key(), "RES-P", "LK-L", 1, tl.version()));

        List<Object> results = runConcurrently(List.of(
                () -> resourceService.startTask("INC-L", "TL", "bob",
                        new TaskActionRequest(key())),
                () -> resourceService.preempt("INC-H", "TH", "carol",
                        new PreemptRequest(key(), "RES-P", "LK-H", 1, th.version(),
                                List.of(new VictimRef("LK-L", 1L))))));

        Object startResult = results.get(0);
        Object preemptResult = results.get(1);
        TaskView task = incidentService.getTask("INC-L", "TL");
        LeaseView victim = resourceService.listLeases("RES-P").leases().stream()
                .filter(l -> l.leaseKey().equals("LK-L")).findFirst().orElseThrow();
        if (task.status().equals("STARTED")) {
            // 启动先提交：抢占因受害任务已 STARTED 被拒
            assertThat(startResult).isInstanceOf(TaskView.class);
            assertThat(preemptResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(victim.status()).isEqualTo("ACTIVE");
        } else {
            // 抢占先提交：租约已撤销，启动不得带失效租约
            assertThat(preemptResult).isInstanceOf(PreemptionView.class);
            assertThat(startResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(victim.status()).isEqualTo("REVOKED");
        }
        // 容量永不超限
        assertThat(resourceService.getResource("RES-P").activeUnits()).isLessThanOrEqualTo(1);
    }

    @Test
    void concurrentCompleteAndPreempt_leaseEndsReleasedOrRevoked() throws Exception {
        commanding("INC-L", "S3", "bob");
        commanding("INC-H", "S1", "carol");
        TaskView tl = createTask("INC-L", "bob", "TL", List.of());
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        resourceService.createResource("ops", new ResourceCreateRequest("RES-X", 1));
        resourceService.requestLease("INC-L", "TL", "bob",
                new LeaseRequest(key(), "RES-X", "LK-L", 1, tl.version()));

        List<Object> results = runConcurrently(List.of(
                () -> incidentService.completeTask("INC-L", "TL", "bob",
                        new TaskActionRequest(key())),
                () -> resourceService.preempt("INC-H", "TH", "carol",
                        new PreemptRequest(key(), "RES-X", "LK-H", 1, th.version(),
                                List.of(new VictimRef("LK-L", 1L))))));

        // 完成必然成功；抢占结果取决于提交顺序
        assertThat(results.get(0)).isInstanceOf(TaskView.class);
        LeaseView victim = resourceService.listLeases("RES-X").leases().stream()
                .filter(l -> l.leaseKey().equals("LK-L")).findFirst().orElseThrow();
        Object preemptResult = results.get(1);
        if (preemptResult instanceof PreemptionView) {
            // 抢占先提交：租约被撤销，新租约占 1 单位
            assertThat(victim.status()).isEqualTo("REVOKED");
            assertThat(resourceService.getResource("RES-X").activeUnits()).isEqualTo(1);
        } else {
            // 完成先提交：租约已释放，抢占 409
            assertThat(preemptResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(victim.status()).isEqualTo("RELEASED");
            assertThat(resourceService.getResource("RES-X").activeUnits()).isZero();
        }
    }

    @Test
    void concurrentPreemptions_atMostOneSucceeds() throws Exception {
        commanding("INC-L", "S3", "bob");
        commanding("INC-H1", "S1", "carol");
        commanding("INC-H2", "S1", "dave");
        TaskView tl = createTask("INC-L", "bob", "TL", List.of());
        TaskView th1 = createTask("INC-H1", "carol", "TH1", List.of());
        TaskView th2 = createTask("INC-H2", "dave", "TH2", List.of());
        resourceService.createResource("ops", new ResourceCreateRequest("RES-Z", 1));
        resourceService.requestLease("INC-L", "TL", "bob",
                new LeaseRequest(key(), "RES-Z", "LK-L", 1, tl.version()));

        List<Object> results = runConcurrently(List.of(
                () -> resourceService.preempt("INC-H1", "TH1", "carol",
                        new PreemptRequest(key(), "RES-Z", "LK-H1", 1, th1.version(),
                                List.of(new VictimRef("LK-L", 1L)))),
                () -> resourceService.preempt("INC-H2", "TH2", "dave",
                        new PreemptRequest(key(), "RES-Z", "LK-H2", 1, th2.version(),
                                List.of(new VictimRef("LK-L", 1L))))));

        long successes = results.stream().filter(PreemptionView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        // 容量永不超限：恰好一条 ACTIVE 租约
        assertThat(resourceService.getResource("RES-Z").activeUnits()).isEqualTo(1);
        assertThat(resourceService.listLeases("RES-Z").leases()).hasSize(2);
    }

    @Test
    void concurrentPreemptAndDependencyChange_serializedConsistently() throws Exception {
        // 受害任务 TB 在 INC-B；并发：INC-A 新建依赖 INC-B 的任务（带租约）与 INC-H 抢占
        commanding("INC-B", "S3", "bob");
        commanding("INC-A", "S3", "alice");
        commanding("INC-H", "S1", "carol");
        TaskView tb = createTask("INC-B", "bob", "TB", List.of());
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        resourceService.createResource("ops", new ResourceCreateRequest("RES-D", 2));
        resourceService.requestLease("INC-B", "TB", "bob",
                new LeaseRequest(key(), "RES-D", "LK-B", 1, tb.version()));

        List<Object> results = runConcurrently(List.of(
                () -> {
                    // 依赖变化：TA 依赖 INC-B，并尝试持有本资源租约
                    // （若抢占先成交占满容量，租约申请可失败，不影响依赖边已提交）
                    TaskView ta = createTask("INC-A", "alice", "TA", List.of("INC-B"));
                    try {
                        resourceService.requestLease("INC-A", "TA", "alice",
                                new LeaseRequest(key(), "RES-D", "LK-A", 1, ta.version()));
                    } catch (ApiException ignored) {
                        // 容量被先成交的抢占占满：合法结果，由后续断言覆盖
                    }
                    return ta;
                },
                () -> resourceService.preempt("INC-H", "TH", "carol",
                        new PreemptRequest(key(), "RES-D", "LK-H", 2, th.version(),
                                List.of(new VictimRef("LK-B", 1L))))));

        Object dependencyChange = results.get(0);
        Object preemptResult = results.get(1);
        assertThat(dependencyChange).isInstanceOf(TaskView.class);
        LeaseView victim = resourceService.listLeases("RES-D").leases().stream()
                .filter(l -> l.leaseKey().equals("LK-B")).findFirst().orElseThrow();
        if (preemptResult instanceof PreemptionView) {
            // 抢占先于依赖边提交：闭包不含 TA，计划合法
            assertThat(victim.status()).isEqualTo("REVOKED");
        } else {
            // 依赖边先提交：闭包要求 LK-A，计划未包含 → 422
            assertThat(preemptResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(victim.status()).isEqualTo("ACTIVE");
        }
        // 容量永不超限
        assertThat(resourceService.getResource("RES-D").activeUnits()).isLessThanOrEqualTo(2);
    }
}
