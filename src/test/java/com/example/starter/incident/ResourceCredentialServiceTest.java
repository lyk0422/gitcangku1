package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAssignRequest;
import com.example.starter.incident.dto.Requests.LeaseReplaceRequest;
import com.example.starter.incident.dto.Requests.LeaseTaskRef;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.LeaseBatchView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.ResourceCredentialsView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.RiskLeaseListView;
import com.example.starter.incident.dto.Responses.TaskGateView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源资质与租约服务测试：覆盖资质登记/撤销、严格覆盖校验（422 缺失或到期）、
 * 批量租约原子性与 leaseKey 幂等指纹、撤销持续门禁（CREDENTIAL_RISK 不得开始/完成）、
 * 合格资源替换恢复与任务门禁原因查询。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceCredentialServiceTest {

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
        incidentService.report(new ReportRequest(incidentKey, "S2", "s", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private ResourceView resource(String resourceKey) {
        return leaseService.registerResource("alice",
                new ResourceRegisterRequest(key(), resourceKey));
    }

    private CredentialView credential(String resourceKey, String code,
                                      Instant validFrom, Instant validUntil) {
        return leaseService.registerCredential(resourceKey, "alice",
                new CredentialRegisterRequest(key(), code, validFrom, validUntil));
    }

    private TaskView highRiskTask(String incidentKey, String taskKey, Instant plannedCompleteAt,
                                  String... codes) {
        return incidentService.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "G", "t", List.of(),
                        List.of(codes), plannedCompleteAt));
    }

    private LeaseBatchView assign(String leaseKey, String resourceKey, Instant start, Instant end,
                                  LeaseTaskRef... refs) {
        return leaseService.assignLeases("alice",
                new LeaseAssignRequest(leaseKey, resourceKey, List.of(refs), start, end));
    }

    private static LeaseTaskRef ref(String incidentKey, String taskKey) {
        return new LeaseTaskRef(incidentKey, taskKey);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static ApiException apiException(ThrowingCallable call, HttpStatus status) {
        try {
            call.call();
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(status);
            return e;
        } catch (Throwable t) {
            throw new AssertionError("期望 ApiException，实际抛出 " + t, t);
        }
        throw new AssertionError("期望抛出 ApiException，但未抛出任何异常");
    }

    @Test
    void registerResourceAndCredential_mainFlow() {
        ResourceView created = resource("RES-1");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.createdBy()).isEqualTo("alice");

        // 重复登记资源 409
        assertApiStatus(() -> resource("RES-1"), HttpStatus.CONFLICT);

        // 登记资质：版本递增
        CredentialView cred = credential("RES-1", "FIRE-A", T0, T0.plusSeconds(7200));
        assertThat(cred.credentialCode()).isEqualTo("FIRE-A");
        assertThat(cred.revoked()).isFalse();
        assertThat(leaseService.listCredentials("RES-1").version()).isEqualTo(2);

        // 未撤销同代码重复登记 409；非法区间 400
        assertApiStatus(() -> credential("RES-1", "FIRE-A", T0, T0.plusSeconds(3600)),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> credential("RES-1", "FIRE-B", T0.plusSeconds(60), T0),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> leaseService.registerCredential("RES-404", "alice",
                new CredentialRegisterRequest(key(), "X", T0, T0.plusSeconds(1))),
                HttpStatus.NOT_FOUND);

        // 撤销：版本递增、记录撤销时刻；重复撤销 409；不存在 404
        CredentialView revoked = leaseService.revokeCredential("RES-1", "FIRE-A", "alice",
                new CredentialRevokeRequest(key()));
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.revokedAt()).isEqualTo(T0);
        assertThat(leaseService.listCredentials("RES-1").version()).isEqualTo(3);
        assertApiStatus(() -> leaseService.revokeCredential("RES-1", "FIRE-A", "alice",
                new CredentialRevokeRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> leaseService.revokeCredential("RES-1", "NOPE", "alice",
                new CredentialRevokeRequest(key())), HttpStatus.NOT_FOUND);

        // 撤销后同代码可重新登记（覆盖有效期并复位撤销标记）
        CredentialView again = credential("RES-1", "FIRE-A", T0, T0.plusSeconds(900));
        assertThat(again.revoked()).isFalse();
        assertThat(again.validUntil()).isEqualTo(T0.plusSeconds(900));
        ResourceCredentialsView view = leaseService.listCredentials("RES-1");
        assertThat(view.version()).isEqualTo(4);
        assertThat(view.credentials()).hasSize(1);

        assertApiStatus(() -> leaseService.listCredentials("RES-404"), HttpStatus.NOT_FOUND);
    }

    @Test
    void createHighRiskTask_validationAndNormalization() {
        commanding("INC-H1", "alice");
        // 高危任务缺 plannedCompleteAt → 400；非高危带 plannedCompleteAt → 400
        assertApiStatus(() -> incidentService.createTask("INC-H1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of("A"), null)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> incidentService.createTask("INC-H1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), List.of(), T0)),
                HttpStatus.BAD_REQUEST);

        // 必需资质集合规范化：去重排序
        TaskView created = highRiskTask("INC-H1", "T-1", T0.plusSeconds(3600), "B", "A", "A");
        assertThat(created.requiredCredentials()).containsExactly("A", "B");
        assertThat(created.plannedCompleteAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(created.status()).isEqualTo("OPEN");

        // 集合换序视为同参：同 commandKey 重放首次响应
        String commandKey = key();
        TaskView first = incidentService.createTask("INC-H1", "alice",
                new TaskCreateRequest(commandKey, "T-2", "G", "t", List.of(),
                        List.of("A", "B"), T0.plusSeconds(3600)));
        TaskView replay = incidentService.createTask("INC-H1", "alice",
                new TaskCreateRequest(commandKey, "T-2", "G", "t", List.of(),
                        List.of("B", "A"), T0.plusSeconds(3600)));
        assertThat(replay).isEqualTo(first);

        // 同 taskKey 不同资质集合 → 409
        assertApiStatus(() -> incidentService.createTask("INC-H1", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "t", List.of(),
                        List.of("A", "C"), T0.plusSeconds(3600))), HttpStatus.CONFLICT);

        // 非高危任务：无资质集合、无计划完成时刻
        TaskView plain = incidentService.createTask("INC-H1", "alice",
                new TaskCreateRequest(key(), "T-3", "G", "t", List.of()));
        assertThat(plain.requiredCredentials()).isEmpty();
        assertThat(plain.plannedCompleteAt()).isNull();
    }

    @Test
    void assignLeases_mainFlowAndLeaseKeyIdempotency() {
        commanding("INC-A1", "alice");
        commanding("INC-A2", "alice");
        resource("RES-A");
        credential("RES-A", "FIRE-A", T0.minusSeconds(60), T0.plusSeconds(7200));
        credential("RES-A", "FIRE-B", T0.minusSeconds(60), T0.plusSeconds(7200));
        highRiskTask("INC-A1", "T-1", T0.plusSeconds(3600), "FIRE-A", "FIRE-B");
        highRiskTask("INC-A2", "T-2", T0.plusSeconds(1800), "FIRE-B");

        // 批量分配：输入换序不影响规范化任务集合
        LeaseBatchView batch = assign("LK-1", "RES-A", T0, T0.plusSeconds(3600),
                ref("INC-A2", "T-2"), ref("INC-A1", "T-1"));
        assertThat(batch.leaseKey()).isEqualTo("LK-1");
        assertThat(batch.resourceKey()).isEqualTo("RES-A");
        assertThat(batch.leases()).hasSize(2);
        // 规范化排序：INC-A1/T-1 在前
        LeaseView first = batch.leases().get(0);
        assertThat(first.incidentKey()).isEqualTo("INC-A1");
        assertThat(first.taskKey()).isEqualTo("T-1");
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.resourceVersion()).isEqualTo(3);
        assertThat(first.credentialCodes()).containsExactly("FIRE-A", "FIRE-B");
        assertThat(first.operator()).isEqualTo("alice");
        assertThat(batch.leases().get(1).credentialCodes()).containsExactly("FIRE-B");

        // 同 leaseKey 重放（任务集合换序）→ 返回首次响应
        LeaseBatchView replayed = assign("LK-1", "RES-A", T0, T0.plusSeconds(3600),
                ref("INC-A1", "T-1"), ref("INC-A2", "T-2"));
        assertThat(replayed).isEqualTo(batch);
        Integer leaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class);
        assertThat(leaseCount).isEqualTo(2);

        // 同 leaseKey 改参（租约时段不同）→ 409
        assertApiStatus(() -> assign("LK-1", "RES-A", T0, T0.plusSeconds(1800),
                ref("INC-A1", "T-1"), ref("INC-A2", "T-2")), HttpStatus.CONFLICT);

        // 资源版本变化（登记新资质）后，同 leaseKey 同参 → 指纹含资源版本，409
        credential("RES-A", "FIRE-C", T0, T0.plusSeconds(3600));
        assertApiStatus(() -> assign("LK-1", "RES-A", T0, T0.plusSeconds(3600),
                ref("INC-A1", "T-1"), ref("INC-A2", "T-2")), HttpStatus.CONFLICT);

        // 已持有生效租约的任务不能重复分配
        assertApiStatus(() -> assign(key(), "RES-A", T0.plusSeconds(7200), T0.plusSeconds(10800),
                ref("INC-A1", "T-1")), HttpStatus.CONFLICT);
    }

    @Test
    void assignLeases_credentialViolation422AndAtomicity() {
        commanding("INC-C1", "alice");
        commanding("INC-C2", "alice");
        resource("RES-C");
        credential("RES-C", "FIRE-A", T0.minusSeconds(60), T0.plusSeconds(3600));
        highRiskTask("INC-C1", "T-1", T0.plusSeconds(1800), "FIRE-A", "FIRE-B");
        highRiskTask("INC-C2", "T-2", T0.plusSeconds(1800), "FIRE-C");

        // 缺失 FIRE-B（未登记）与 FIRE-C（未登记）→ 422 并列出缺失资质
        ApiException ex = apiException(() -> assign("LK-C1", "RES-C", T0, T0.plusSeconds(3600),
                ref("INC-C1", "T-1"), ref("INC-C2", "T-2")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.code()).isEqualTo("CREDENTIAL_VIOLATION");
        @SuppressWarnings("unchecked")
        List<Object> details = (List<Object>) ex.details();
        assertThat(details).hasSize(2);
        assertThat(details.toString()).contains("FIRE-B", "FIRE-C", "MISSING");

        // 整单回滚：任一任务失败，全部租约不创建
        Integer leaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class);
        assertThat(leaseCount).isZero();

        // 失败不占键：补齐资质后同 leaseKey 重试成功
        credential("RES-C", "FIRE-B", T0.minusSeconds(60), T0.plusSeconds(3600));
        credential("RES-C", "FIRE-C", T0.minusSeconds(60), T0.plusSeconds(3600));
        LeaseBatchView ok = assign("LK-C1", "RES-C", T0, T0.plusSeconds(3600),
                ref("INC-C1", "T-1"), ref("INC-C2", "T-2"));
        assertThat(ok.leases()).hasSize(2);
    }

    @Test
    void assignLeases_strictCoverageBoundary() {
        commanding("INC-B1", "alice");
        resource("RES-B");
        Instant planned = T0.plusSeconds(3600);
        highRiskTask("INC-B1", "T-1", planned, "FIRE-A");

        // validUntil == 计划完成时刻：未严格覆盖 → 422 NOT_COVERING
        credential("RES-B", "FIRE-A", T0.minusSeconds(60), planned);
        ApiException ex = apiException(() -> assign(key(), "RES-B", T0, T0.plusSeconds(3600),
                ref("INC-B1", "T-1")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.details().toString()).contains("NOT_COVERING");

        // validFrom 晚于计划完成时刻 → 未覆盖
        leaseService.revokeCredential("RES-B", "FIRE-A", "alice", new CredentialRevokeRequest(key()));
        credential("RES-B", "FIRE-A", planned.plusSeconds(1), planned.plusSeconds(7200));
        assertApiStatus(() -> assign(key(), "RES-B", T0, T0.plusSeconds(3600),
                ref("INC-B1", "T-1")), HttpStatus.UNPROCESSABLE_ENTITY);

        // validUntil 严格晚于计划完成时刻 → 通过
        leaseService.revokeCredential("RES-B", "FIRE-A", "alice", new CredentialRevokeRequest(key()));
        credential("RES-B", "FIRE-A", T0.minusSeconds(60), planned.plusSeconds(1));
        LeaseBatchView ok = assign(key(), "RES-B", T0, T0.plusSeconds(3600), ref("INC-B1", "T-1"));
        assertThat(ok.leases()).hasSize(1);
    }

    @Test
    void assignLeases_conflictsAndDependencyGate() {
        commanding("INC-D1", "alice");
        commanding("INC-D2", "alice");
        commanding("INC-D3", "alice");
        resource("RES-D");
        credential("RES-D", "FIRE-A", T0.minusSeconds(60), T0.plusSeconds(10800));
        highRiskTask("INC-D1", "T-1", T0.plusSeconds(3600), "FIRE-A");
        highRiskTask("INC-D2", "T-2", T0.plusSeconds(3600), "FIRE-A");
        highRiskTask("INC-D3", "T-3", T0.plusSeconds(3600), "FIRE-A");
        // 非高危任务
        incidentService.createTask("INC-D3", "alice",
                new TaskCreateRequest(key(), "T-PLAIN", "G", "t", List.of()));

        // 非高危任务不能分配租约 → 400
        assertApiStatus(() -> assign(key(), "RES-D", T0, T0.plusSeconds(3600),
                ref("INC-D3", "T-PLAIN")), HttpStatus.BAD_REQUEST);
        // 租约时段非法 / 任务集合为空 → 400
        assertApiStatus(() -> assign(key(), "RES-D", T0.plusSeconds(3600), T0,
                ref("INC-D1", "T-1")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> leaseService.assignLeases("alice",
                new LeaseAssignRequest(key(), "RES-D", List.of(), T0, T0.plusSeconds(3600))),
                HttpStatus.BAD_REQUEST);
        // 资源不存在 → 404；任务不存在 → 404
        assertApiStatus(() -> assign(key(), "RES-404", T0, T0.plusSeconds(3600),
                ref("INC-D1", "T-1")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> assign(key(), "RES-D", T0, T0.plusSeconds(3600),
                ref("INC-D1", "T-404")), HttpStatus.NOT_FOUND);

        // 依赖门禁：T-2 被 INC-D3 阻塞（未解除）→ 409 且整单回滚
        incidentService.createTask("INC-D2", "alice",
                new TaskCreateRequest(key(), "T-BLOCK", "G", "t", List.of("INC-D3")));
        incidentService.createTask("INC-D2", "alice",
                new TaskCreateRequest(key(), "T-2B", "G", "t", List.of("INC-D3"),
                        List.of("FIRE-A"), T0.plusSeconds(3600)));
        ApiException blocked = apiException(() -> assign(key(), "RES-D", T0, T0.plusSeconds(3600),
                ref("INC-D2", "T-2B")), HttpStatus.CONFLICT);
        assertThat(blocked.details().toString()).contains("INC-D3");

        // 首批成功
        assign("LK-D1", "RES-D", T0, T0.plusSeconds(3600), ref("INC-D1", "T-1"));
        // 同时段相交冲突 → 409；时段相接（半开区间）不冲突
        assertApiStatus(() -> assign(key(), "RES-D", T0.plusSeconds(1800), T0.plusSeconds(5400),
                ref("INC-D2", "T-2")), HttpStatus.CONFLICT);
        LeaseBatchView adjacent = assign(key(), "RES-D", T0.plusSeconds(3600),
                T0.plusSeconds(7200), ref("INC-D2", "T-2"));
        assertThat(adjacent.leases()).hasSize(1);
    }

    @Test
    void revokeCredential_riskTransitionAndImmutableRecord() {
        commanding("INC-R1", "alice");
        resource("RES-R");
        credential("RES-R", "FIRE-A", T0.minusSeconds(60), T0.plusSeconds(10800));
        credential("RES-R", "FIRE-B", T0.minusSeconds(60), T0.plusSeconds(10800));
        highRiskTask("INC-R1", "T-ACTIVE", T0.plusSeconds(3600), "FIRE-A");
        highRiskTask("INC-R1", "T-DONE", T0.plusSeconds(3600), "FIRE-A");
        highRiskTask("INC-R1", "T-EXPIRED", T0.plusSeconds(3600), "FIRE-A");
        highRiskTask("INC-R1", "T-OTHER", T0.plusSeconds(3600), "FIRE-B");
        assign("LK-R1", "RES-R", T0.minusSeconds(1800), T0.plusSeconds(3600),
                ref("INC-R1", "T-ACTIVE"));
        assign("LK-R2", "RES-R", T0.plusSeconds(3600), T0.plusSeconds(7200),
                ref("INC-R1", "T-DONE"));
        // 已完成任务：完成时租约释放
        ((ControllableClock) clock).setInstant(T0.plusSeconds(3600));
        incidentService.completeTask("INC-R1", "T-DONE", "alice", new TaskActionRequest(key()));
        ((ControllableClock) clock).setInstant(T0);
        // 已过期租约（lease_end 早于当前时刻）
        assign("LK-R3", "RES-R", T0.minusSeconds(7200), T0.minusSeconds(3600),
                ref("INC-R1", "T-EXPIRED"));
        assign("LK-R4", "RES-R", T0.plusSeconds(7200), T0.plusSeconds(10800),
                ref("INC-R1", "T-OTHER"));

        // 撤销 FIRE-A：未来有效租约 T-ACTIVE 转 CREDENTIAL_RISK；
        // 已完成任务不改写；已过期租约不受影响；不覆盖 FIRE-A 的租约不受影响
        leaseService.revokeCredential("RES-R", "FIRE-A", "alice", new CredentialRevokeRequest(key()));

        assertThat(incidentService.getTask("INC-R1", "T-ACTIVE").status())
                .isEqualTo("CREDENTIAL_RISK");
        assertThat(incidentService.getTask("INC-R1", "T-DONE").status()).isEqualTo("DONE");
        assertThat(incidentService.getTask("INC-R1", "T-EXPIRED").status()).isEqualTo("OPEN");
        assertThat(incidentService.getTask("INC-R1", "T-OTHER").status()).isEqualTo("OPEN");

        // 不可变风险记录：仅 T-ACTIVE 一条
        Integer riskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credential_risk_records", Integer.class);
        assertThat(riskCount).isEqualTo(1);

        // 风险租约查询：按资源与按事件
        RiskLeaseListView byResource = leaseService.riskLeasesByResource("RES-R");
        assertThat(byResource.riskLeases()).hasSize(1);
        assertThat(byResource.riskLeases().get(0).lease().taskKey()).isEqualTo("T-ACTIVE");
        assertThat(byResource.riskLeases().get(0).lease().status()).isEqualTo("CREDENTIAL_RISK");
        assertThat(byResource.riskLeases().get(0).riskRecords()).hasSize(1);
        assertThat(byResource.riskLeases().get(0).riskRecords().get(0).credentialCode())
                .isEqualTo("FIRE-A");
        assertThat(byResource.riskLeases().get(0).riskRecords().get(0).revokedAt()).isEqualTo(T0);
        RiskLeaseListView byIncident = leaseService.riskLeasesByIncident("INC-R1");
        assertThat(byIncident.riskLeases()).hasSize(1);

        // 任务门禁查询：CREDENTIAL_RISK 不得开始或完成
        TaskGateView gate = leaseService.taskGate("INC-R1", "T-ACTIVE");
        assertThat(gate.status()).isEqualTo("CREDENTIAL_RISK");
        assertThat(gate.canStart()).isFalse();
        assertThat(gate.canComplete()).isFalse();
        assertThat(gate.riskCredentials()).containsExactly("FIRE-A");
        assertThat(gate.gateReasons()).isNotEmpty();

        // 开始/完成均被门禁拒绝（任务无阻塞，依赖满足也不能绕过）
        assertApiStatus(() -> incidentService.startTask("INC-R1", "T-ACTIVE", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> incidentService.completeTask("INC-R1", "T-ACTIVE", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void replaceLease_recoveryRestoresTask() {
        commanding("INC-P1", "alice");
        resource("RES-OLD");
        resource("RES-NEW");
        credential("RES-OLD", "FIRE-A", T0.minusSeconds(60), T0.plusSeconds(10800));
        highRiskTask("INC-P1", "T-1", T0.plusSeconds(3600), "FIRE-A");
        assign("LK-P1", "RES-OLD", T0.minusSeconds(1800), T0.plusSeconds(3600),
                ref("INC-P1", "T-1"));
        // 先开始（风险前状态 IN_PROGRESS），再撤销进入风险态
        incidentService.startTask("INC-P1", "T-1", "alice", new TaskActionRequest(key()));
        leaseService.revokeCredential("RES-OLD", "FIRE-A", "alice",
                new CredentialRevokeRequest(key()));
        assertThat(incidentService.getTask("INC-P1", "T-1").status()).isEqualTo("CREDENTIAL_RISK");

        // 非当前指挥人不能替换 → 409
        assertApiStatus(() -> leaseService.replaceLease("INC-P1", "T-1", "bob",
                new LeaseReplaceRequest(key(), "RES-NEW")), HttpStatus.CONFLICT);
        // 新资源资质不合格 → 422
        assertApiStatus(() -> leaseService.replaceLease("INC-P1", "T-1", "alice",
                new LeaseReplaceRequest(key(), "RES-NEW")), HttpStatus.UNPROCESSABLE_ENTITY);

        // 合格资源替换：旧租约 REPLACED、新租约 ACTIVE 沿用原时段、任务恢复风险前状态
        credential("RES-NEW", "FIRE-A", T0.minusSeconds(60), T0.plusSeconds(10800));
        LeaseView replaced = leaseService.replaceLease("INC-P1", "T-1", "alice",
                new LeaseReplaceRequest("LK-P2", "RES-NEW"));
        assertThat(replaced.status()).isEqualTo("ACTIVE");
        assertThat(replaced.resourceKey()).isEqualTo("RES-NEW");
        assertThat(replaced.leaseStart()).isEqualTo(T0.minusSeconds(1800));
        assertThat(replaced.leaseEnd()).isEqualTo(T0.plusSeconds(3600));
        assertThat(incidentService.getTask("INC-P1", "T-1").status()).isEqualTo("IN_PROGRESS");
        // 风险租约查询已清空，但不可变风险记录保留
        assertThat(leaseService.riskLeasesByIncident("INC-P1").riskLeases()).isEmpty();
        Integer riskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credential_risk_records", Integer.class);
        assertThat(riskCount).isEqualTo(1);
        Integer replacedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'REPLACED'"
                        + " AND replaced_by = 'LK-P2'", Integer.class);
        assertThat(replacedCount).isEqualTo(1);

        // 恢复后可完成；完成时新租约释放
        incidentService.completeTask("INC-P1", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(incidentService.getTask("INC-P1", "T-1").status()).isEqualTo("DONE");
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status IN ('ACTIVE','CREDENTIAL_RISK')",
                Integer.class);
        assertThat(activeCount).isZero();

        // 非风险状态任务不能替换 → 409
        assertApiStatus(() -> leaseService.replaceLease("INC-P1", "T-1", "alice",
                new LeaseReplaceRequest(key(), "RES-NEW")), HttpStatus.CONFLICT);
    }

    @Test
    void startTask_gatesAndResolveInteraction() {
        commanding("INC-S1", "alice");
        commanding("INC-S2", "alice");
        // 阻塞未解除不能开始
        incidentService.createTask("INC-S1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-S2")));
        assertApiStatus(() -> incidentService.startTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        TaskGateView blockedGate = leaseService.taskGate("INC-S1", "T-1");
        assertThat(blockedGate.canStart()).isFalse();
        assertThat(blockedGate.unresolvedBlockers()).containsExactly("INC-S2");

        // 阻塞解除后开始：OPEN → IN_PROGRESS；重复开始 409
        incidentService.changeStatus("INC-S2", "alice", new StatusRequest(key(), "CONTAINED"));
        TaskView started = incidentService.startTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        assertApiStatus(() -> incidentService.startTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        // IN_PROGRESS 任务同样阻塞事件解决门禁
        incidentService.changeStatus("INC-S1", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiStatus(() -> incidentService.changeStatus("INC-S1", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);

        // IN_PROGRESS 可完成；完成后可解决
        TaskView done = incidentService.completeTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        incidentService.changeStatus("INC-S1", "alice", new StatusRequest(key(), "RESOLVED"));
        assertThat(incidentService.get("INC-S1").status()).isEqualTo("RESOLVED");
    }

    @Test
    void cancelCredentialRiskTask_releasesLease() {
        commanding("INC-X1", "alice");
        resource("RES-X");
        credential("RES-X", "FIRE-A", T0.minusSeconds(60), T0.plusSeconds(10800));
        highRiskTask("INC-X1", "T-1", T0.plusSeconds(3600), "FIRE-A");
        assign("LK-X1", "RES-X", T0, T0.plusSeconds(3600), ref("INC-X1", "T-1"));
        leaseService.revokeCredential("RES-X", "FIRE-A", "alice",
                new CredentialRevokeRequest(key()));
        assertThat(incidentService.getTask("INC-X1", "T-1").status()).isEqualTo("CREDENTIAL_RISK");

        // 风险任务可取消，租约释放
        TaskView cancelled = incidentService.cancelTask("INC-X1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        Integer holding = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status IN ('ACTIVE','CREDENTIAL_RISK')",
                Integer.class);
        assertThat(holding).isZero();
    }
}
