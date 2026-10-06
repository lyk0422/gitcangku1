package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.example.starter.incident.dto.Requests.BarrierRegisterRequest;
import com.example.starter.incident.dto.Requests.BarrierRevokeRequest;
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.RiskModelSetupRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 合成残余风险模型服务层测试（真实 H2 事务）：题干合成场景主流程
 * （R=100、L=20，两条 1/2 屏障残余 25 拒绝遏制，再完成 1/5 屏障残余 20 允许，
 * CONTAINED 后撤销它 409）、参数与状态拒绝分支、版本乐观锁、
 * 幂等重放（权限先于重放）、CLOSED 禁写与交接共享模型视图。
 */
@SpringBootTest
class IncidentRiskServiceTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRiskService riskService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_containment_snapshots");
        jdbc.update("DELETE FROM incident_risk_barriers");
        jdbc.update("DELETE FROM incident_risk_models");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "残余风险场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void setupModel(String incidentKey, String actor, int r, int l) {
        riskService.setupModel(incidentKey, actor, new RiskModelSetupRequest(key(), r, l));
    }

    private void register(String incidentKey, String actor, String barrierKey, String taskKey,
                          int p, int q) {
        riskService.registerBarrier(incidentKey, actor,
                new BarrierRegisterRequest(key(), barrierKey, taskKey, p, q));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, null));
    }

    private void completeTask(String incidentKey, String actor, String taskKey) {
        incidentService.completeTask(incidentKey, taskKey, actor, new TaskActionRequest(key()));
    }

    private static void assertApiStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void syntheticScenario_residualGateAndRevokeGuard() {
        commanding("INC-R1", "alice");
        setupModel("INC-R1", "alice", 100, 20);
        createTask("INC-R1", "alice", "T-1");
        createTask("INC-R1", "alice", "T-2");
        createTask("INC-R1", "alice", "T-3");
        register("INC-R1", "alice", "B-1", "T-1", 1, 2);
        register("INC-R1", "alice", "B-2", "T-2", 1, 2);
        register("INC-R1", "alice", "B-3", "T-3", 1, 5);

        // 两条各削减 1/2 的屏障 DONE：残余 100×1/2×1/2 = 25 > 20，拒绝遏制（422）
        completeTask("INC-R1", "alice", "T-1");
        completeTask("INC-R1", "alice", "T-2");
        RiskModelView view = riskService.getModel("INC-R1");
        assertThat(view.residualNumerator()).isEqualTo(25);
        assertThat(view.residualDenominator()).isEqualTo(1);
        assertThat(view.containable()).isFalse();
        assertThatThrownBy(() -> incidentService.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "CONTAINED")))
                .satisfies(e -> assertApiStatus(e, HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(incidentService.get("INC-R1").status()).isEqualTo("COMMANDING");

        // 再完成削减 1/5 的屏障：残余 100×1/2×1/2×4/5 = 20 ≤ 20，允许遏制
        completeTask("INC-R1", "alice", "T-3");
        view = riskService.getModel("INC-R1");
        assertThat(view.residualNumerator()).isEqualTo(20);
        assertThat(view.residualDenominator()).isEqualTo(1);
        assertThat(view.containable()).isTrue();
        incidentService.changeStatus("INC-R1", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(incidentService.get("INC-R1").status()).isEqualTo("CONTAINED");

        // 遏制冻结了 3 条有效屏障快照（身份、比率、任务状态 DONE）
        view = riskService.getModel("INC-R1");
        assertThat(view.containmentSnapshot()).hasSize(3);
        assertThat(view.containmentSnapshot())
                .allSatisfy(s -> assertThat(s.taskStatus()).isEqualTo("DONE"));

        // CONTAINED 后撤销 1/5 屏障：后态残余 25 > 20，409 拒绝，不能偷偷失去防护
        assertThatThrownBy(() -> riskService.revokeBarrier("INC-R1", "B-3", "alice",
                new BarrierRevokeRequest(key(), 0L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        view = riskService.getModel("INC-R1");
        assertThat(view.version()).isZero();
        assertThat(view.barriers()).allSatisfy(b -> assertThat(b.revoked()).isFalse());
        assertThat(view.residualNumerator()).isEqualTo(20);
    }

    @Test
    void setupValidation_rejectsInvalidParams() {
        commanding("INC-R2", "alice");
        assertThatThrownBy(() -> setupModel("INC-R2", "alice", 0, 0))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> setupModel("INC-R2", "alice", 1001, 0))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> setupModel("INC-R2", "alice", 100, -1))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        // L 必须小于 R
        assertThatThrownBy(() -> setupModel("INC-R2", "alice", 100, 100))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> setupModel("INC-R2", "alice", 100, 150))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        // 非当前指挥人 → 409
        assertThatThrownBy(() -> setupModel("INC-R2", "bob", 100, 20))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        // 全部失败不占键也不落库：合法参数仍可设置
        setupModel("INC-R2", "alice", 100, 20);
        assertThat(riskService.getModel("INC-R2").initialRisk()).isEqualTo(100);
        // 重复设置 → 409
        assertThatThrownBy(() -> setupModel("INC-R2", "alice", 100, 20))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
    }

    @Test
    void registerBarrierValidation_rejectsInvalidParams() {
        commanding("INC-R3", "alice");
        createTask("INC-R3", "alice", "T-1");
        // 模型未设置 → 409
        assertThatThrownBy(() -> register("INC-R3", "alice", "B-1", "T-1", 1, 2))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        setupModel("INC-R3", "alice", 100, 20);
        // 任务不存在 → 404；比率非法 → 400
        assertThatThrownBy(() -> register("INC-R3", "alice", "B-1", "T-9", 1, 2))
                .satisfies(e -> assertApiStatus(e, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> register("INC-R3", "alice", "B-1", "T-1", 3, 2))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> register("INC-R3", "alice", "B-1", "T-1", 1, 0))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> register("INC-R3", "alice", "B-1", "T-1", 1, 1001))
                .satisfies(e -> assertApiStatus(e, HttpStatus.BAD_REQUEST));
        register("INC-R3", "alice", "B-1", "T-1", 1, 2);
        // barrierKey 重复 → 409
        assertThatThrownBy(() -> register("INC-R3", "alice", "B-1", "T-1", 1, 2))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        // 绑定非 OPEN 任务 → 409
        createTask("INC-R3", "alice", "T-2");
        completeTask("INC-R3", "alice", "T-2");
        assertThatThrownBy(() -> register("INC-R3", "alice", "B-2", "T-2", 1, 2))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
    }

    @Test
    void registerBarrier_maxFiveActiveEnforced() {
        commanding("INC-R4", "alice");
        setupModel("INC-R4", "alice", 100, 20);
        for (int i = 1; i <= 5; i++) {
            createTask("INC-R4", "alice", "T-" + i);
            register("INC-R4", "alice", "B-" + i, "T-" + i, 1, 2);
        }
        // 第 6 个未撤销屏障 → 409
        createTask("INC-R4", "alice", "T-6");
        assertThatThrownBy(() -> register("INC-R4", "alice", "B-6", "T-6", 1, 2))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        // 撤销一个后释放名额，可再登记
        riskService.revokeBarrier("INC-R4", "B-5", "alice", new BarrierRevokeRequest(key(), 0L));
        register("INC-R4", "alice", "B-6", "T-6", 1, 2);
        assertThat(riskService.getModel("INC-R4").barriers()).hasSize(6);
        assertThat(riskService.getModel("INC-R4").barriers()
                .stream().filter(b -> !b.revoked()).count()).isEqualTo(5);
    }

    @Test
    void updateAndRevoke_versionOptimisticLock() {
        commanding("INC-R5", "alice");
        setupModel("INC-R5", "alice", 100, 20);
        createTask("INC-R5", "alice", "T-1");
        createTask("INC-R5", "alice", "T-2");
        register("INC-R5", "alice", "B-1", "T-1", 1, 2);

        // expectedVersion 不匹配 → 409；屏障不存在 → 404
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R5", "B-1", "alice",
                new BarrierUpdateRequest(key(), "T-1", 3, 4, 1L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R5", "B-9", "alice",
                new BarrierUpdateRequest(key(), "T-1", 3, 4, 0L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.NOT_FOUND));

        // 换绑到非 OPEN 任务 → 409；换绑到 OPEN 任务成功且版本加一
        completeTask("INC-R5", "alice", "T-2");
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R5", "B-1", "alice",
                new BarrierUpdateRequest(key(), "T-2", 3, 4, 0L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        createTask("INC-R5", "alice", "T-3");
        RiskModelView view = riskService.updateBarrier("INC-R5", "B-1", "alice",
                new BarrierUpdateRequest(key(), "T-3", 3, 4, 0L));
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.barriers().get(0).taskKey()).isEqualTo("T-3");
        assertThat(view.barriers().get(0).numerator()).isEqualTo(3);
        assertThat(view.barriers().get(0).denominator()).isEqualTo(4);

        // 撤销成功版本再加一；已撤销屏障不可再修改或重复撤销
        view = riskService.revokeBarrier("INC-R5", "B-1", "alice",
                new BarrierRevokeRequest(key(), 1L));
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.barriers().get(0).revoked()).isTrue();
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R5", "B-1", "alice",
                new BarrierUpdateRequest(key(), "T-3", 1, 2, 2L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        assertThatThrownBy(() -> riskService.revokeBarrier("INC-R5", "B-1", "alice",
                new BarrierRevokeRequest(key(), 2L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
    }

    @Test
    void idempotency_replayConflictAndPermissionFirst() {
        commanding("INC-R6", "alice");
        String setupKey = key();
        RiskModelView first = riskService.setupModel("INC-R6", "alice",
                new RiskModelSetupRequest(setupKey, 100, 20));
        // 同键同参重放首次完整结果，不产生第二次写入
        RiskModelView replayed = riskService.setupModel("INC-R6", "alice",
                new RiskModelSetupRequest(setupKey, 100, 20));
        assertThat(replayed).isEqualTo(first);
        Integer modelCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_risk_models", Integer.class);
        assertThat(modelCount).isEqualTo(1);
        // 同键改参 → 409
        assertThatThrownBy(() -> riskService.setupModel("INC-R6", "alice",
                new RiskModelSetupRequest(setupKey, 100, 30)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));

        // 失败不占键：版本不匹配 409 后，同键修正参数可成功
        createTask("INC-R6", "alice", "T-1");
        register("INC-R6", "alice", "B-1", "T-1", 1, 2);
        String updateKey = key();
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R6", "B-1", "alice",
                new BarrierUpdateRequest(updateKey, "T-1", 3, 4, 9L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
        RiskModelView updated = riskService.updateBarrier("INC-R6", "B-1", "alice",
                new BarrierUpdateRequest(updateKey, "T-1", 3, 4, 0L));
        assertThat(updated.version()).isEqualTo(1);

        // 权限先于重放：非指挥人持已成功请求的 commandKey 与同参数 → 409 权限拒绝，
        // 不会重放首次成功结果，也不产生二次效果
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R6", "B-1", "bob",
                new BarrierUpdateRequest(updateKey, "T-1", 3, 4, 0L)))
                .satisfies(e -> assertThat(e).isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).contains("指挥人");
                }));
        assertThat(riskService.getModel("INC-R6").version()).isEqualTo(1);
    }

    @Test
    void closedIncident_rejectsModelConfig() {
        commanding("INC-R7", "alice");
        setupModel("INC-R7", "alice", 100, 20);
        createTask("INC-R7", "alice", "T-1");
        register("INC-R7", "alice", "B-1", "T-1", 9, 10);
        completeTask("INC-R7", "alice", "T-1");
        incidentService.changeStatus("INC-R7", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-R7", "alice", new StatusRequest(key(), "RESOLVED"));
        createTask("INC-R7", "alice", "T-2");
        incidentService.changeStatus("INC-R7", "alice", new StatusRequest(key(), "CLOSED"));

        // CLOSED 后不接受新配置：登记/修改/撤销均 422
        assertThatThrownBy(() -> register("INC-R7", "alice", "B-2", "T-2", 1, 2))
                .satisfies(e -> assertApiStatus(e, HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R7", "B-1", "alice",
                new BarrierUpdateRequest(key(), "T-1", 1, 2, 0L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> riskService.revokeBarrier("INC-R7", "B-1", "alice",
                new BarrierRevokeRequest(key(), 0L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.UNPROCESSABLE_ENTITY));
        // 只读视图仍可用，冻结快照保留
        assertThat(riskService.getModel("INC-R7").containmentSnapshot()).hasSize(1);
    }

    @Test
    void handover_sharesModelViewWithNewCommander() {
        commanding("INC-R8", "alice");
        setupModel("INC-R8", "alice", 100, 20);
        createTask("INC-R8", "alice", "T-1");
        register("INC-R8", "alice", "B-1", "T-1", 1, 2);

        // 交接后新指挥人接管同一模型视图，旧指挥人失去配置权
        incidentService.initiateTransfer("INC-R8", "alice", new TransferRequest(key(), "bob"));
        incidentService.acceptTransfer("INC-R8", "bob", new TransferAcceptRequest(key()));
        RiskModelView view = riskService.updateBarrier("INC-R8", "B-1", "bob",
                new BarrierUpdateRequest(key(), "T-1", 3, 4, 0L));
        assertThat(view.version()).isEqualTo(1);
        assertThatThrownBy(() -> riskService.updateBarrier("INC-R8", "B-1", "alice",
                new BarrierUpdateRequest(key(), "T-1", 1, 2, 1L)))
                .satisfies(e -> assertApiStatus(e, HttpStatus.CONFLICT));
    }

    @Test
    void containmentSnapshot_frozenAgainstLaterStrengthening() {
        commanding("INC-R9", "alice");
        setupModel("INC-R9", "alice", 100, 20);
        createTask("INC-R9", "alice", "T-1");
        register("INC-R9", "alice", "B-1", "T-1", 4, 5);
        completeTask("INC-R9", "alice", "T-1");
        incidentService.changeStatus("INC-R9", "alice", new StatusRequest(key(), "CONTAINED"));

        // 遏制后合法加强（4/5 → 9/10，残余 20 → 10）允许，但旧快照依据不变
        RiskModelView view = riskService.updateBarrier("INC-R9", "B-1", "alice",
                new BarrierUpdateRequest(key(), "T-1", 9, 10, 0L));
        assertThat(view.residualNumerator()).isEqualTo(10);
        assertThat(view.barriers().get(0).numerator()).isEqualTo(9);
        assertThat(view.containmentSnapshot()).hasSize(1);
        assertThat(view.containmentSnapshot().get(0).numerator()).isEqualTo(4);
        assertThat(view.containmentSnapshot().get(0).denominator()).isEqualTo(5);
        assertThat(view.containmentSnapshot().get(0).taskStatus()).isEqualTo("DONE");
    }
}
