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
import com.example.starter.incident.dto.Requests.BarrierUpdateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.RiskModelInitRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.RiskModelView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 残余风险模型并发边界测试：事件行锁与 requestId 唯一约束下，
 * 同键并发重放安全、同版本并发修改至多一个成功、并发遏制只冻结一次，
 * 竞争不暴露为唯一键 500。真实 H2 事务验证。
 */
@SpringBootTest
class RiskModelConcurrencyTest {

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
    void concurrentRegisterSameRequestId_singleEffect() throws Exception {
        commanding("RISK-300");
        service.createTask("RISK-300", "alice", new TaskCreateRequest(key(), "T-1", "G", "t", null));
        riskService.initModel("RISK-300", "alice", new RiskModelInitRequest(key(), 100, 20));

        String requestId = key();
        List<Callable<RiskModelView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> riskService.registerBarrier("RISK-300", "alice",
                    new BarrierRegisterRequest(requestId, "B-1", "T-1", 1, 2)));
        }
        List<Object> results = runConcurrently(tasks);

        List<RiskModelView> successes = results.stream().filter(RiskModelView.class::isInstance)
                .map(RiskModelView.class::cast).toList();
        // 同键同参并发：全部返回首次结果，屏障只登记一次，不产生唯一键 500
        assertThat(successes).hasSize(3);
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(riskService.getView("RISK-300").barriers()).hasSize(1);
        assertThat(riskService.getView("RISK-300").version()).isEqualTo(2);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, requestId);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentUpdatesSameExpectedVersion_exactlyOneWins() throws Exception {
        commanding("RISK-301");
        service.createTask("RISK-301", "alice", new TaskCreateRequest(key(), "T-1", "G", "t", null));
        riskService.initModel("RISK-301", "alice", new RiskModelInitRequest(key(), 100, 20));
        riskService.registerBarrier("RISK-301", "alice",
                new BarrierRegisterRequest(key(), "B-1", "T-1", 1, 2));

        List<Object> results = runConcurrently(List.of(
                () -> riskService.updateBarrier("RISK-301", "B-1", "alice",
                        new BarrierUpdateRequest(key(), 1, 3, 2L)),
                () -> riskService.updateBarrier("RISK-301", "B-1", "alice",
                        new BarrierUpdateRequest(key(), 1, 4, 2L))));

        long successes = results.stream().filter(RiskModelView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        // 同一期望版本并发修改：至多一个成功，版本只推进一次
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(riskService.getView("RISK-301").version()).isEqualTo(3);
    }

    @Test
    void concurrentContainment_exactlyOneFreezes() throws Exception {
        commanding("RISK-302");
        service.createTask("RISK-302", "alice", new TaskCreateRequest(key(), "T-1", "G", "t", null));
        riskService.initModel("RISK-302", "alice", new RiskModelInitRequest(key(), 100, 50));
        riskService.registerBarrier("RISK-302", "alice",
                new BarrierRegisterRequest(key(), "B-1", "T-1", 1, 2));
        service.completeTask("RISK-302", "T-1", "alice", new TaskActionRequest(key()));

        List<Object> results = runConcurrently(List.of(
                () -> service.changeStatus("RISK-302", "alice",
                        new StatusRequest(key(), "CONTAINED")),
                () -> service.changeStatus("RISK-302", "alice",
                        new StatusRequest(key(), "CONTAINED"))));

        long successes = results.stream()
                .filter(r -> !(r instanceof Exception)).count();
        long illegal = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 422).count();
        // 并发遏制：一个成功，另一个因状态已前进而 422；冻结只发生一次
        assertThat(successes).isEqualTo(1);
        assertThat(illegal).isEqualTo(1);
        assertThat(service.history("RISK-302").statusHistory())
                .filteredOn(s -> "CONTAINED".equals(s.toStatus())).hasSize(1);
        RiskModelView view = riskService.getView("RISK-302");
        assertThat(view.containment().residual()).isEqualTo("50/1");
        assertThat(view.containment().barriers()).hasSize(1);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_containment_barriers", Integer.class);
        assertThat(snapshots).isEqualTo(1);
    }
}
