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

import com.example.starter.incident.dto.Requests.BarrierRegisterRequest;
import com.example.starter.incident.dto.Requests.BarrierRevokeRequest;
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.RiskModelSetupRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 残余风险模型并发边界测试（真实 H2 事务）：并发登记屏障上限兜底、
 * 同 expectedVersion 并发修改仅一个成功、同 commandKey 并发单次生效、
 * 并发遏制与屏障撤销按事务提交顺序形成合法结果（不偷偷失去防护）。
 */
@SpringBootTest
class IncidentRiskConcurrencyTest {

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
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
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
    void concurrentRegister_maxFiveEnforced() throws Exception {
        commanding("INC-C1", "alice");
        riskService.setupModel("INC-C1", "alice", new RiskModelSetupRequest(key(), 100, 20));
        for (int i = 1; i <= 8; i++) {
            incidentService.createTask("INC-C1", "alice",
                    new TaskCreateRequest(key(), "T-" + i, "G", "任务" + i, null));
        }
        List<Callable<RiskModelView>> tasks = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String taskKey = "T-" + i;
            String barrierKey = "B-" + i;
            tasks.add(() -> riskService.registerBarrier("INC-C1", "alice",
                    new BarrierRegisterRequest(key(), barrierKey, taskKey, 1, 2)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RiskModelView.class::isInstance).count();
        assertThat(successes).isEqualTo(5);
        assertThat(countConflicts(results)).isEqualTo(3);
        Integer barrierCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_risk_barriers WHERE revoked = 0", Integer.class);
        assertThat(barrierCount).isEqualTo(5);
    }

    @Test
    void concurrentUpdate_sameExpectedVersion_singleSuccess() throws Exception {
        commanding("INC-C2", "alice");
        riskService.setupModel("INC-C2", "alice", new RiskModelSetupRequest(key(), 100, 20));
        incidentService.createTask("INC-C2", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "任务", null));
        riskService.registerBarrier("INC-C2", "alice",
                new BarrierRegisterRequest(key(), "B-1", "T-1", 1, 2));

        // 两个并发修改携带相同 expectedVersion=0：事件行锁串行化，仅一个成功，版本只加一次
        List<Object> results = runConcurrently(List.of(
                () -> riskService.updateBarrier("INC-C2", "B-1", "alice",
                        new BarrierUpdateRequest(key(), "T-1", 3, 4, 0L)),
                () -> riskService.updateBarrier("INC-C2", "B-1", "alice",
                        new BarrierUpdateRequest(key(), "T-1", 4, 5, 0L))));

        long successes = results.stream().filter(RiskModelView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        RiskModelView view = riskService.getModel("INC-C2");
        assertThat(view.version()).isEqualTo(1);
    }

    @Test
    void concurrentRegister_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-C3", "alice");
        riskService.setupModel("INC-C3", "alice", new RiskModelSetupRequest(key(), 100, 20));
        incidentService.createTask("INC-C3", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "任务", null));
        String commandKey = key();
        List<Callable<RiskModelView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> riskService.registerBarrier("INC-C3", "alice",
                    new BarrierRegisterRequest(commandKey, "B-1", "T-1", 1, 2)));
        }
        List<Object> results = runConcurrently(tasks);

        List<RiskModelView> successes = results.stream().filter(RiskModelView.class::isInstance)
                .map(RiskModelView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(riskService.getModel("INC-C3").barriers()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentContainAndRevoke_commitOrderWins() throws Exception {
        commanding("INC-C4", "alice");
        riskService.setupModel("INC-C4", "alice", new RiskModelSetupRequest(key(), 100, 20));
        for (int i = 1; i <= 3; i++) {
            incidentService.createTask("INC-C4", "alice",
                    new TaskCreateRequest(key(), "T-" + i, "G", "任务" + i, null));
        }
        riskService.registerBarrier("INC-C4", "alice",
                new BarrierRegisterRequest(key(), "B-1", "T-1", 1, 2));
        riskService.registerBarrier("INC-C4", "alice",
                new BarrierRegisterRequest(key(), "B-2", "T-2", 1, 2));
        riskService.registerBarrier("INC-C4", "alice",
                new BarrierRegisterRequest(key(), "B-3", "T-3", 1, 5));
        for (int i = 1; i <= 3; i++) {
            incidentService.completeTask("INC-C4", "T-" + i, "alice", new TaskActionRequest(key()));
        }
        // 当前残余 20 = L：并发遏制与撤销 1/5 屏障（撤销后残余 25 超限）
        List<Object> results = runConcurrently(List.of(
                () -> incidentService.changeStatus("INC-C4", "alice",
                        new StatusRequest(key(), "CONTAINED")),
                () -> riskService.revokeBarrier("INC-C4", "B-3", "alice",
                        new BarrierRevokeRequest(key(), 0L))));

        String status = incidentService.get("INC-C4").status();
        RiskModelView view = riskService.getModel("INC-C4");
        if ("CONTAINED".equals(status)) {
            // 遏制先提交：撤销时事件已 CONTAINED 且后态超限 → 409，屏障仍在
            assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(view.barriers()).allSatisfy(b -> assertThat(b.revoked()).isFalse());
            assertThat(view.containmentSnapshot()).hasSize(3);
        } else {
            // 撤销先提交：残余升至 25，遏制 422 拒绝，事件仍 COMMANDING
            assertThat(status).isEqualTo("COMMANDING");
            assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(view.barriers().stream().filter(b -> b.barrierKey().equals("B-3"))
                    .findFirst().orElseThrow().revoked()).isTrue();
            assertThat(view.residualNumerator()).isEqualTo(25);
        }
    }
}
