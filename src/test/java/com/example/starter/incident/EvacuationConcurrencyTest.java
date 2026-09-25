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

import com.example.starter.incident.dto.Requests.ExemptionGrantRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskBatchDispatchRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Responses.ZoneView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 疏散门禁并发与幂等边界测试（真实 H2 MODE=MySQL、真实并发线程、超时协调）：
 * 同指纹并发登记只产生一个区域、同作用域并发授予只产生一条豁免、
 * 区域登记与任务开始按事务提交顺序裁决、同 commandKey 并发派工单次生效。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class EvacuationConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Autowired
    private IncidentService service;
    @Autowired
    private EvacuationService evacuation;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM evacuation_exemptions");
        jdbc.update("DELETE FROM evacuation_zones");
        jdbc.update("DELETE FROM zone_command_keys");
        jdbc.update("DELETE FROM task_dispatch_leases");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发疏散", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
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
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
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

    @Test
    void concurrentRegisterSameFingerprint_singleZone() throws Exception {
        commanding("INC-C1", "alice");
        // 三个不同 zoneKey、不同 commandKey 但同事件版本/网格/窗口/等级/操作者 → 同指纹
        List<Callable<?>> tasks = new ArrayList<>();
        for (String zk : List.of("Z1", "Z2", "Z3")) {
            tasks.add(() -> evacuation.registerZone("INC-C1", "alice",
                    new ZoneRegisterRequest(key(), zk, "HIGH", List.of("X1"),
                            T0, T0.plusSeconds(3600))));
        }
        List<Object> results = runConcurrently(tasks);
        List<ZoneView> zones = results.stream().filter(ZoneView.class::isInstance)
                .map(ZoneView.class::cast).toList();
        // 全部成功响应都指向同一区域（同指纹重放）
        assertThat(zones).hasSize(3);
        assertThat(zones).allSatisfy(z -> {
            assertThat(z.zoneKey()).isEqualTo(zones.get(0).zoneKey());
            assertThat(z.version()).isEqualTo(zones.get(0).version());
        });
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evacuation_zones WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key='INC-C1')", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentGrantSameScope_singleExemption() throws Exception {
        commanding("INC-C2", "alice");
        evacuation.registerZone("INC-C2", "alice", new ZoneRegisterRequest(key(), "Z-A",
                "HIGH", List.of("X1"), T0, T0.plusSeconds(3600)));
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> evacuation.grantExemption("INC-C2", "Z-A", "alice",
                    new ExemptionGrantRequest(key(), "T-PRE", "X1")));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results.stream().filter(r -> !(r instanceof Exception)).count()).isEqualTo(3);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evacuation_exemptions WHERE exempt_task_key='T-PRE'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentZoneRegisterAndStart_commitOrderAdjudicated() throws Exception {
        commanding("INC-C3", "alice");
        service.createTask("INC-C3", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", "X1", List.of()));

        // 并发：登记当前生效区域 与 开始任务。同事件行锁串行化，按提交顺序裁决。
        List<Object> results = runConcurrently(List.of(
                () -> evacuation.registerZone("INC-C3", "alice",
                        new ZoneRegisterRequest(key(), "Z-A", "HIGH", List.of("X1"),
                                T0.minusSeconds(10), T0.plusSeconds(3600))),
                () -> service.startTask("INC-C3", "T-1", "alice",
                        new TaskActionRequest(key()))));

        String taskStatus = service.getTask("INC-C3", "T-1").status();
        Object startResult = results.get(1);
        if ("IN_PROGRESS".equals(taskStatus)) {
            // 开始先提交：区域登记时任务已非未开始，不被阻断；区域仍登记成功
            assertThat(startResult).isInstanceOf(com.example.starter.incident.dto.Responses.TaskView.class);
            assertThat(evacuation.listZones("INC-C3").zones()).hasSize(1);
        } else {
            // 区域先提交：未开始任务被阻断，开始 422
            assertThat(taskStatus).isEqualTo("EVACUATION_BLOCKED");
            assertThat(startResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status().value()).isEqualTo(422));
        }
    }

    @Test
    void concurrentBatchDispatchSameCommandKey_singleEffect() throws Exception {
        commanding("INC-C4", "alice");
        service.createTask("INC-C4", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", "Y1", List.of()));
        String ck = key();
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.batchDispatch("INC-C4", "alice",
                    new TaskBatchDispatchRequest(ck, List.of("T-1"))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results.stream().filter(r -> !(r instanceof Exception)).count()).isEqualTo(3);
        assertThat(service.getTask("INC-C4", "T-1").status()).isEqualTo("DISPATCHED");
        Integer leases = jdbc.queryForObject("SELECT COUNT(*) FROM task_dispatch_leases",
                Integer.class);
        assertThat(leases).isEqualTo(1);
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, ck);
        assertThat(keyRows).isEqualTo(1);
    }
}
