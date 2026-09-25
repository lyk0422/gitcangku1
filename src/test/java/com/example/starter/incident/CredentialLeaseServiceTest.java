package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAllocateRequest;
import com.example.starter.incident.dto.Requests.LeaseItem;
import com.example.starter.incident.dto.Requests.LeaseReplaceRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.CredentialRevokeView;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.LeaseAllocateView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.TaskGateView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源资质租约核心服务测试（真实 H2 MySQL 兼容内存库）：
 * 覆盖资质严格覆盖、批量租约原子性、撤销持续门禁（依赖满足不可绕过）、
 * 替换恢复、已完成任务不改写、leaseKey 幂等指纹与查询门禁原因。
 */
@SpringBootTest
class CredentialLeaseServiceTest {

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
        incidentService.report(new ReportRequest(incidentKey, "S2", "资质场景", "reporter"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView highRiskTask(String incidentKey, String commander, String taskKey,
                                  List<String> required) {
        return incidentService.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "G", "高危任务", List.of(), required));
    }

    private CredentialView register(String resource, String code, Instant validUntil) {
        return leaseService.register(new CredentialRegisterRequest(key(), resource, code,
                null, validUntil));
    }

    private LeaseItem item(String incident, String task, String resource, Instant start,
                           Instant end) {
        return new LeaseItem(incident, task, resource, start, end);
    }

    @Test
    void allocate_coverage_mainFlow_thenStart() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1", "C2"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        register("R-1", "C2", t0.plus(10, ChronoUnit.HOURS));

        LeaseAllocateView view = leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.HOURS)))));
        assertThat(view.created()).hasSize(1);
        LeaseView lease = view.created().get(0);
        assertThat(lease.resourceId()).isEqualTo("R-1");
        assertThat(lease.requiredCredentials()).containsExactly("C1", "C2");
        assertThat(lease.current()).isTrue();
        assertThat(lease.resourceVersion()).isEqualTo(1);

        TaskGateView gate = leaseService.taskGate("INC-1", "T-1");
        assertThat(gate.canStart()).isTrue();
        assertThat(gate.credentialRisk()).isFalse();

        TaskView started = incidentService.startTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void allocate_missingCredential_422ListsMissing() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1", "C2"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));

        assertThatThrownBy(() -> leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("CREDENTIAL_NOT_COVERED");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, List<String>> details =
                            (java.util.Map<String, List<String>>) e.details();
                    assertThat(details.get("missing")).containsExactly("C2");
                    assertThat(details.get("expired")).isEmpty();
                });
        // 失败不留租约
        Integer leaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class);
        assertThat(leaseCount).isZero();
    }

    @Test
    void allocate_validityMustStrictlyCoverPlannedEnd() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        Instant leaseEnd = t0.plus(2, ChronoUnit.HOURS);
        // 有效期恰好等于计划完成时刻：不满足“严格覆盖”
        register("R-1", "C1", leaseEnd);

        assertThatThrownBy(() -> leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), leaseEnd)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, List<String>> details =
                            (java.util.Map<String, List<String>>) e.details();
                    assertThat(details.get("expired")).containsExactly("C1");
                    assertThat(details.get("missing")).isEmpty();
                });

        // 续期至严格晚于计划完成时刻后分配成功，版本 +1
        CredentialView renewed = leaseService.register(new CredentialRegisterRequest(key(),
                "R-1", "C1", null, leaseEnd.plusSeconds(1)));
        assertThat(renewed.version()).isEqualTo(2);
        LeaseAllocateView view = leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), leaseEnd))));
        assertThat(view.created()).hasSize(1);
        assertThat(view.created().get(0).resourceVersion()).isEqualTo(2);
    }

    @Test
    void allocate_notYetEffectiveCredential_422Expired() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        // 资质生效时刻晚于分配时刻
        leaseService.register(new CredentialRegisterRequest(key(), "R-1", "C1",
                Instant.now().plusSeconds(3600), t0.plus(10, ChronoUnit.HOURS)));

        assertThatThrownBy(() -> leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, List<String>> details =
                            (java.util.Map<String, List<String>>) e.details();
                    assertThat(details.get("expired")).containsExactly("C1");
                });
    }

    @Test
    void batchAllocate_anyFailure_rollsBackAll() {
        commanding("INC-1", "alice");
        commanding("INC-2", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        highRiskTask("INC-2", "alice", "T-2", List.of("C1"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        // R-2 没有任何资质：第二项失败
        assertThatThrownBy(() -> leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(
                        item("INC-1", "T-1", "R-1", t0.plus(1, ChronoUnit.HOURS),
                                t0.plus(2, ChronoUnit.HOURS)),
                        item("INC-2", "T-2", "R-2", t0.plus(1, ChronoUnit.HOURS),
                                t0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> details =
                            (java.util.Map<String, Object>) e.details();
                    assertThat(details.get("taskKey")).isEqualTo("T-2");
                    assertThat((List<String>) details.get("missing")).containsExactly("C1");
                });
        Integer leaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class);
        assertThat(leaseCount).isZero();
    }

    @Test
    void batchAllocate_resourceTimeConflict_rejected() {
        commanding("INC-1", "alice");
        commanding("INC-2", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        highRiskTask("INC-2", "alice", "T-2", List.of("C1"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        // 同一资源时段重叠
        leaseService.allocate("alice", new LeaseAllocateRequest(key(), List.of(
                item("INC-1", "T-1", "R-1", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(3, ChronoUnit.HOURS)))));
        assertThatThrownBy(() -> leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(
                        item("INC-2", "T-2", "R-1", t0.plus(2, ChronoUnit.HOURS),
                                t0.plus(4, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        // 相邻时段不冲突
        LeaseAllocateView adjacent = leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(
                        item("INC-2", "T-2", "R-1", t0.plus(3, ChronoUnit.HOURS),
                                t0.plus(4, ChronoUnit.HOURS)))));
        assertThat(adjacent.created()).hasSize(1);
    }

    @Test
    void revoke_persistentGate_untilQualifiedReplace() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        leaseService.allocate("alice", new LeaseAllocateRequest(key(), List.of(
                item("INC-1", "T-1", "R-1", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(3, ChronoUnit.HOURS)))));
        incidentService.startTask("INC-1", "T-1", "alice", new TaskActionRequest(key()));

        CredentialRevokeView revokeView = leaseService.revoke("R-1", "C1", "alice",
                new CredentialRevokeRequest(key(), "资质造假"));
        assertThat(revokeView.credential().status()).isEqualTo("REVOKED");
        assertThat(revokeView.triggeredRisks()).hasSize(1);
        assertThat(revokeView.triggeredRisks().get(0).credentialCode()).isEqualTo("C1");
        assertThat(revokeView.triggeredRisks().get(0).reason()).isEqualTo("资质造假");

        TaskView risky = incidentService.getTask("INC-1", "T-1");
        assertThat(risky.status()).isEqualTo("CREDENTIAL_RISK");
        assertThat(risky.credentialRisk()).isTrue();

        // 风险任务不得完成（依赖满足也不能绕过）
        assertThatThrownBy(() -> incidentService.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("ILLEGAL_TRANSITION");
                });

        TaskGateView gate = leaseService.taskGate("INC-1", "T-1");
        assertThat(gate.credentialRisk()).isTrue();
        assertThat(gate.canComplete()).isFalse();
        assertThat(gate.reasons()).anyMatch(r -> r.startsWith("CREDENTIAL_RISK"));

        // 风险租约查询
        assertThat(leaseService.listRisks("INC-1").risks()).hasSize(1);

        // 用同样不合格的资源替换仍被拒
        assertThatThrownBy(() -> leaseService.replace("INC-1", "T-1", "alice",
                new LeaseReplaceRequest(key(), "R-1", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(3, ChronoUnit.HOURS))))
                .isInstanceOf(ApiException.class);

        // 以合格新资源替换：风险解除，旧租约 REPLACED，可完成
        register("R-2", "C1", t0.plus(10, ChronoUnit.HOURS));
        LeaseView replacement = leaseService.replace("INC-1", "T-1", "alice",
                new LeaseReplaceRequest(key(), "R-2", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(3, ChronoUnit.HOURS)));
        assertThat(replacement.resourceId()).isEqualTo("R-2");
        assertThat(replacement.current()).isTrue();
        assertThat(incidentService.getTask("INC-1", "T-1").status()).isEqualTo("IN_PROGRESS");
        Integer oldLeaseState = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'REPLACED'", Integer.class);
        assertThat(oldLeaseState).isEqualTo(1);

        TaskView done = incidentService.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        // 不可变风险记录仍保留
        assertThat(leaseService.listRisks("INC-1").risks()).hasSize(1);
    }

    @Test
    void revoke_riskOnOpenTask_replaceRestoresOpenThenStart() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        leaseService.allocate("alice", new LeaseAllocateRequest(key(), List.of(
                item("INC-1", "T-1", "R-1", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(3, ChronoUnit.HOURS)))));

        // 开始前撤销：OPEN 任务同样进入风险门禁
        leaseService.revoke("R-1", "C1", "alice", new CredentialRevokeRequest(key(), "提前撤销"));
        assertThat(incidentService.getTask("INC-1", "T-1").status())
                .isEqualTo("CREDENTIAL_RISK");
        assertThatThrownBy(() -> incidentService.startTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()))).isInstanceOf(ApiException.class);

        register("R-2", "C1", t0.plus(10, ChronoUnit.HOURS));
        leaseService.replace("INC-1", "T-1", "alice",
                new LeaseReplaceRequest(key(), "R-2", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(3, ChronoUnit.HOURS)));
        assertThat(incidentService.getTask("INC-1", "T-1").status()).isEqualTo("OPEN");
        TaskView started = incidentService.startTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void revoke_doneTaskNotChangedAndNoRiskRecord() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        leaseService.allocate("alice", new LeaseAllocateRequest(key(), List.of(
                item("INC-1", "T-1", "R-1", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(3, ChronoUnit.HOURS)))));
        incidentService.startTask("INC-1", "T-1", "alice", new TaskActionRequest(key()));
        incidentService.completeTask("INC-1", "T-1", "alice", new TaskActionRequest(key()));

        CredentialRevokeView revokeView = leaseService.revoke("R-1", "C1", "alice",
                new CredentialRevokeRequest(key(), "事后撤销"));
        assertThat(revokeView.triggeredRisks()).isEmpty();
        assertThat(incidentService.getTask("INC-1", "T-1").status()).isEqualTo("DONE");
        assertThat(leaseService.listRisks("INC-1").risks()).isEmpty();
    }

    @Test
    void highRiskTaskWithoutLease_cannotStart() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        assertThatThrownBy(() -> incidentService.startTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("CREDENTIAL_NOT_COVERED");
                });
    }

    @Test
    void nonHighRiskTask_needsNoLease() {
        commanding("INC-1", "alice");
        incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "普通任务", List.of(), List.of()));
        TaskView started = incidentService.startTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        TaskView done = incidentService.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void allocate_failureDoesNotConsumeCommandKey() {
        commanding("INC-1", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        String commandKey = key();
        // 资质缺失失败后补登资质，同键重试应成功（失败不占键）
        assertThatThrownBy(() -> leaseService.allocate("alice",
                new LeaseAllocateRequest(commandKey, List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOf(ApiException.class);
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        LeaseAllocateView view = leaseService.allocate("alice",
                new LeaseAllocateRequest(commandKey, List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.HOURS)))));
        assertThat(view.created()).hasSize(1);
    }

    @Test
    void allocate_taskSetReorder_sameKeyReplaysFirstResponse() {
        commanding("INC-1", "alice");
        commanding("INC-2", "alice");
        highRiskTask("INC-1", "alice", "T-1", List.of("C1"));
        highRiskTask("INC-2", "alice", "T-2", List.of("C1"));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        String commandKey = key();
        LeaseAllocateRequest first = new LeaseAllocateRequest(commandKey, List.of(
                item("INC-2", "T-2", "R-1", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(2, ChronoUnit.HOURS)),
                item("INC-1", "T-1", "R-1", t0.plus(3, ChronoUnit.HOURS),
                        t0.plus(4, ChronoUnit.HOURS))));
        LeaseAllocateView view1 = leaseService.allocate("alice", first);
        // 换序后同键同参：重放首次响应
        LeaseAllocateRequest reordered = new LeaseAllocateRequest(commandKey, List.of(
                item("INC-1", "T-1", "R-1", t0.plus(3, ChronoUnit.HOURS),
                        t0.plus(4, ChronoUnit.HOURS)),
                item("INC-2", "T-2", "R-1", t0.plus(1, ChronoUnit.HOURS),
                        t0.plus(2, ChronoUnit.HOURS))));
        LeaseAllocateView view2 = leaseService.allocate("alice", reordered);
        assertThat(view2).isEqualTo(view1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_leases", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void requiredCredentialsSet_reorderIsSameParamOnCreate() {
        commanding("INC-1", "alice");
        TaskView first = incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("C2", "C1")));
        TaskView again = incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("C1", "C2")));
        assertThat(again).isEqualTo(first);
        assertThat(first.requiredCredentials()).containsExactly("C1", "C2");
        assertThat(incidentService.listTasks("INC-1").tasks()).hasSize(1);
    }

    @Test
    void dependencyGate_blocksAllocationUntilBlockersContained() {
        commanding("INC-1", "alice");
        commanding("INC-2", "bob");
        incidentService.createTask("INC-1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-2"), List.of("C1")));
        register("R-1", "C1", t0.plus(10, ChronoUnit.HOURS));
        assertThatThrownBy(() -> leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        incidentService.changeStatus("INC-2", "bob", new StatusRequest(key(), "CONTAINED"));
        LeaseAllocateView view = leaseService.allocate("alice",
                new LeaseAllocateRequest(key(), List.of(item("INC-1", "T-1", "R-1",
                        t0.plus(1, ChronoUnit.HOURS), t0.plus(2, ChronoUnit.HOURS)))));
        assertThat(view.created()).hasSize(1);
    }

    @Test
    void queryResourceCredentials_sortedByCode() {
        register("R-9", "C2", t0.plus(10, ChronoUnit.HOURS));
        register("R-9", "C1", t0.plus(10, ChronoUnit.HOURS));
        assertThat(leaseService.listCredentials("R-9").credentials()).extracting(CredentialView::credentialCode)
                .containsExactly("C1", "C2");
        assertThat(leaseService.listCredentials("R-EMPTY").credentials()).isEmpty();
    }
}
