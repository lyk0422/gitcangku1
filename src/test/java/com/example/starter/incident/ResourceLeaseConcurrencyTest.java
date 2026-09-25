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

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAssignRequest;
import com.example.starter.incident.dto.Requests.LeaseTaskRef;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.LeaseBatchView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源租约并发边界测试：验证同 leaseKey 并发单次生效、同时段冲突租约唯一胜者、
 * 资质撤销与任务完成按提交顺序裁决、撤销与分配经租约域锁串行后状态一致。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceLeaseConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceLeaseService leaseService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM credential_risk_records");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM task_required_credentials");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM resources");
        jdbc.update("DELETE FROM lease_domain_lock");
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

    private void resourceWithCredential(String resourceKey, String code) {
        leaseService.registerResource("alice", new ResourceRegisterRequest(key(), resourceKey));
        leaseService.registerCredential(resourceKey, "alice",
                new CredentialRegisterRequest(key(), code, T0.minusSeconds(60),
                        T0.plusSeconds(10800)));
    }

    private void highRiskTask(String incidentKey, String taskKey, String... codes) {
        incidentService.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "G", "t", List.of(),
                        List.of(codes), T0.plusSeconds(3600)));
    }

    private static LeaseTaskRef ref(String incidentKey, String taskKey) {
        return new LeaseTaskRef(incidentKey, taskKey);
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
    void concurrentAssign_sameLeaseKey_singleEffect() throws Exception {
        commanding("INC-K1", "alice");
        commanding("INC-K2", "alice");
        resourceWithCredential("RES-K", "FIRE-A");
        highRiskTask("INC-K1", "T-1", "FIRE-A");
        highRiskTask("INC-K2", "T-2", "FIRE-A");

        List<Callable<LeaseBatchView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> leaseService.assignLeases("alice",
                    new LeaseAssignRequest("LK-SAME", "RES-K",
                            List.of(ref("INC-K1", "T-1"), ref("INC-K2", "T-2")),
                            T0, T0.plusSeconds(3600))));
        }
        List<Object> results = runConcurrently(calls);

        List<LeaseBatchView> successes = results.stream()
                .filter(LeaseBatchView.class::isInstance)
                .map(LeaseBatchView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且租约只创建一批
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer leaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class);
        assertThat(leaseCount).isEqualTo(2);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = 'LK-SAME'", Integer.class);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentAssign_overlappingPeriods_singleWinner() throws Exception {
        commanding("INC-O1", "alice");
        commanding("INC-O2", "alice");
        resourceWithCredential("RES-O", "FIRE-A");
        highRiskTask("INC-O1", "T-1", "FIRE-A");
        highRiskTask("INC-O2", "T-2", "FIRE-A");

        // 同资源同时段两批租约并发：租约域锁串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> leaseService.assignLeases("alice",
                        new LeaseAssignRequest("LK-O1", "RES-O", List.of(ref("INC-O1", "T-1")),
                                T0, T0.plusSeconds(3600))),
                () -> leaseService.assignLeases("alice",
                        new LeaseAssignRequest("LK-O2", "RES-O", List.of(ref("INC-O2", "T-2")),
                                T0.plusSeconds(1800), T0.plusSeconds(5400)))));

        long successes = results.stream().filter(LeaseBatchView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        Integer leaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class);
        assertThat(leaseCount).isEqualTo(1);
    }

    @Test
    void concurrentRevokeAndComplete_commitOrderWins() throws Exception {
        commanding("INC-V1", "alice");
        resourceWithCredential("RES-V", "FIRE-A");
        highRiskTask("INC-V1", "T-1", "FIRE-A");
        leaseService.assignLeases("alice",
                new LeaseAssignRequest("LK-V1", "RES-V", List.of(ref("INC-V1", "T-1")),
                        T0.minusSeconds(1800), T0.plusSeconds(3600)));

        // 并发：撤销资质 与 完成任务
        List<Object> results = runConcurrently(List.of(
                () -> leaseService.revokeCredential("RES-V", "FIRE-A", "alice",
                        new CredentialRevokeRequest(key())),
                () -> incidentService.completeTask("INC-V1", "T-1", "alice",
                        new TaskActionRequest(key()))));

        String taskStatus = incidentService.getTask("INC-V1", "T-1").status();
        Object completeResult = results.get(1);
        Integer riskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credential_risk_records", Integer.class);
        if ("DONE".equals(taskStatus)) {
            // 完成先提交：已完成任务不改写，租约释放，无风险记录
            assertThat(completeResult).isInstanceOf(
                    com.example.starter.incident.dto.Responses.TaskView.class);
            assertThat(riskCount).isZero();
            Integer holding = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM resource_leases"
                            + " WHERE status IN ('ACTIVE','CREDENTIAL_RISK')", Integer.class);
            assertThat(holding).isZero();
        } else {
            // 撤销先提交：任务进入 CREDENTIAL_RISK，完成被门禁 409 拒绝
            assertThat(taskStatus).isEqualTo("CREDENTIAL_RISK");
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(riskCount).isEqualTo(1);
        }
    }

    @Test
    void concurrentAssignAndRevoke_serializedConsistent() throws Exception {
        commanding("INC-S1", "alice");
        resourceWithCredential("RES-S", "FIRE-A");
        highRiskTask("INC-S1", "T-1", "FIRE-A");

        // 并发：分配租约 与 撤销资质（租约域锁串行化，按提交顺序裁决）
        List<Object> results = runConcurrently(List.of(
                () -> leaseService.assignLeases("alice",
                        new LeaseAssignRequest("LK-S1", "RES-S", List.of(ref("INC-S1", "T-1")),
                                T0.minusSeconds(1800), T0.plusSeconds(3600))),
                () -> leaseService.revokeCredential("RES-S", "FIRE-A", "alice",
                        new CredentialRevokeRequest(key()))));

        Object assignResult = results.get(0);
        String taskStatus = incidentService.getTask("INC-S1", "T-1").status();
        Integer riskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credential_risk_records", Integer.class);
        if (assignResult instanceof LeaseBatchView) {
            // 分配先提交：撤销随后使其转入 CREDENTIAL_RISK 并写风险记录
            assertThat(taskStatus).isEqualTo("CREDENTIAL_RISK");
            assertThat(riskCount).isEqualTo(1);
        } else {
            // 撤销先提交：分配时资质已撤销，422 且不留租约
            assertThat(assignResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(taskStatus).isEqualTo("OPEN");
            assertThat(riskCount).isZero();
            Integer leaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM resource_leases", Integer.class);
            assertThat(leaseCount).isZero();
        }
    }
}
