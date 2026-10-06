package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.example.starter.incident.dto.Requests.BarrierRegisterRequest;
import com.example.starter.incident.dto.Requests.BarrierRevokeRequest;
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.RiskModelInitRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 残余风险模型服务测试：精确有理数边界（不舍入）、失败不占键、
 * 屏障上限、任务完成/取消驱动屏障生效、重复初始化冲突与同参重放。真实 H2 事务验证。
 */
@SpringBootTest
class RiskModelServiceTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private RiskModelService riskService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_containment_barriers");
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
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey) {
        service.report(new ReportRequest(incidentKey, "S1", "s", "r"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
    }

    private void addTask(String incidentKey, String taskKey, boolean done) {
        service.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "G", "t", null));
        if (done) {
            service.completeTask(incidentKey, taskKey, "alice", new TaskActionRequest(key()));
        }
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void exactRationalBoundaryNoRounding() {
        commanding("RISK-200");
        addTask("RISK-200", "T-1", false);
        riskService.initModel("RISK-200", "alice", new RiskModelInitRequest(key(), 1000, 333));
        riskService.registerBarrier("RISK-200", "alice",
                new BarrierRegisterRequest(key(), "B-1", "T-1", 2, 3));
        service.completeTask("RISK-200", "T-1", "alice", new TaskActionRequest(key()));

        // 残余 1000×1/3 = 1000/3 ≈ 333.33 > 333：舍入会误判，精确比较有理数拒绝
        RiskModelView view = riskService.getView("RISK-200");
        assertThat(view.residual()).isEqualTo("1000/3");
        assertThat(view.residualWithinLimit()).isFalse();
        assertThatThrownBy(() -> service.changeStatus("RISK-200", "alice",
                new StatusRequest(key(), "CONTAINED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("RESIDUAL_RISK_EXCEEDED");
                });

        // 加强到 667/1000：残余 1000×333/1000 = 333，恰好等于上限，允许遏制
        riskService.updateBarrier("RISK-200", "B-1", "alice",
                new BarrierUpdateRequest(key(), 667, 1000, 2L));
        service.changeStatus("RISK-200", "alice", new StatusRequest(key(), "CONTAINED"));
        RiskModelView contained = riskService.getView("RISK-200");
        assertThat(contained.residual()).isEqualTo("333/1");
        assertThat(contained.containment().residual()).isEqualTo("333/1");
        assertThat(contained.containment().barriers()).hasSize(1);
    }

    @Test
    void failureDoesNotOccupyRequestId() {
        commanding("RISK-201");
        addTask("RISK-201", "T-1", false);
        riskService.initModel("RISK-201", "alice", new RiskModelInitRequest(key(), 100, 20));

        // 失败（任务不存在 404）不占键：同 requestId 换正确参数可成功
        String requestId = key();
        assertApiStatus(() -> riskService.registerBarrier("RISK-201", "alice",
                new BarrierRegisterRequest(requestId, "B-1", "T-404", 1, 2)), HttpStatus.NOT_FOUND);
        RiskModelView view = riskService.registerBarrier("RISK-201", "alice",
                new BarrierRegisterRequest(requestId, "B-1", "T-1", 1, 2));
        assertThat(view.version()).isEqualTo(2);
        service.completeTask("RISK-201", "T-1", "alice", new TaskActionRequest(key()));

        // 失败（期望版本不匹配 409）不占键：同 requestId 换正确版本可成功
        String updateId = key();
        assertApiStatus(() -> riskService.updateBarrier("RISK-201", "B-1", "alice",
                new BarrierUpdateRequest(updateId, 1, 4, 99L)), HttpStatus.CONFLICT);
        RiskModelView updated = riskService.updateBarrier("RISK-201", "B-1", "alice",
                new BarrierUpdateRequest(updateId, 1, 4, 2L));
        assertThat(updated.version()).isEqualTo(3);
        assertThat(updated.residual()).isEqualTo("75/1");
    }

    @Test
    void maxFiveActiveBarriersAndRevokeFreesSlot() {
        commanding("RISK-202");
        addTask("RISK-202", "T-1", false);
        riskService.initModel("RISK-202", "alice", new RiskModelInitRequest(key(), 100, 20));
        for (int i = 1; i <= 5; i++) {
            riskService.registerBarrier("RISK-202", "alice",
                    new BarrierRegisterRequest(key(), "B-" + i, "T-1", 1, 2));
        }
        // 第 6 个未撤销屏障 → 409
        assertApiStatus(() -> riskService.registerBarrier("RISK-202", "alice",
                new BarrierRegisterRequest(key(), "B-6", "T-1", 1, 2)), HttpStatus.CONFLICT);
        // 撤销一个（当前版本 6）后释放名额，可再登记
        riskService.revokeBarrier("RISK-202", "B-5", "alice", new BarrierRevokeRequest(key(), 6L));
        RiskModelView view = riskService.registerBarrier("RISK-202", "alice",
                new BarrierRegisterRequest(key(), "B-6", "T-1", 1, 2));
        assertThat(view.version()).isEqualTo(8);
        assertThat(view.barriers()).hasSize(6);
    }

    @Test
    void taskCompletionAndCancellationDriveEffectiveness() {
        commanding("RISK-203");
        addTask("RISK-203", "T-1", false);
        addTask("RISK-203", "T-2", false);
        riskService.initModel("RISK-203", "alice", new RiskModelInitRequest(key(), 100, 20));
        riskService.registerBarrier("RISK-203", "alice",
                new BarrierRegisterRequest(key(), "B-1", "T-1", 4, 5));
        riskService.registerBarrier("RISK-203", "alice",
                new BarrierRegisterRequest(key(), "B-2", "T-2", 1, 2));

        // 任务 OPEN：屏障不计入，残余 100/1
        assertThat(riskService.getView("RISK-203").residual()).isEqualTo("100/1");
        // 完成 T-1：残余 100×1/5 = 20
        service.completeTask("RISK-203", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(riskService.getView("RISK-203").residual()).isEqualTo("20/1");
        // 取消 T-2：屏障永不生效，残余不变，共享视图反映任务状态
        service.cancelTask("RISK-203", "T-2", "alice", new TaskActionRequest(key()));
        RiskModelView view = riskService.getView("RISK-203");
        assertThat(view.residual()).isEqualTo("20/1");
        assertThat(view.barriers().get(1).taskStatus()).isEqualTo("CANCELLED");
        assertThat(view.barriers().get(1).effective()).isFalse();
        // 残余达标，遏制成功并冻结有效屏障
        service.changeStatus("RISK-203", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(riskService.getView("RISK-203").containment().barriers()).hasSize(1);
    }

    @Test
    void initTwiceConflictAndSameParamsReplay() {
        commanding("RISK-204");
        String requestId = key();
        RiskModelView first = riskService.initModel("RISK-204", "alice",
                new RiskModelInitRequest(requestId, 100, 20));
        // 不同 requestId 重复初始化 → 409
        assertApiStatus(() -> riskService.initModel("RISK-204", "alice",
                new RiskModelInitRequest(key(), 50, 10)), HttpStatus.CONFLICT);
        // 同 requestId 同参重放 → 返回首次完整结果
        RiskModelView replay = riskService.initModel("RISK-204", "alice",
                new RiskModelInitRequest(requestId, 100, 20));
        assertThat(replay).isEqualTo(first);
        // 同 requestId 改参 → 409
        assertApiStatus(() -> riskService.initModel("RISK-204", "alice",
                new RiskModelInitRequest(requestId, 100, 30)), HttpStatus.CONFLICT);
    }
}
