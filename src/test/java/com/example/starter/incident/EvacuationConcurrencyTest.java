package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.example.starter.incident.dto.Requests.DispatchItem;
import com.example.starter.incident.dto.Requests.DispatchRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Responses.DispatchView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.ZoneView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 疏散区域与派工并发边界测试：同指纹区域并发登记单次生效、
 * 跨事件并发派工竞争同一资源由全局锁串行化、同任务并发开始仅一次生效。
 */
@SpringBootTest
class EvacuationConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private EvacuationService zones;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_task_zone_blocks");
        jdbc.update("DELETE FROM zone_exemptions");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM incident_zones");
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

    @Test
    void concurrentRegister_sameFingerprint_singleZone() throws Exception {
        commanding("INC-CZ", "alice");
        List<Callable<ZoneView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> zones.registerZone("INC-CZ", "alice",
                    new ZoneRegisterRequest(key(), List.of("A1", "B2"), T0.plusSeconds(100),
                            T0.plusSeconds(200), "HIGH")));
        }
        List<Object> results = runConcurrently(calls);

        List<ZoneView> successes = results.stream().filter(ZoneView.class::isInstance)
                .map(ZoneView.class::cast).toList();
        // 同指纹同操作者：全部重放首次结果，区域只登记一次
        assertThat(successes).hasSize(3);
        assertThat(successes).allSatisfy(z -> assertThat(z.zoneKey())
                .isEqualTo(successes.get(0).zoneKey()));
        Integer zoneCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_zones", Integer.class);
        assertThat(zoneCount).isEqualTo(1);
    }

    @Test
    void concurrentDispatch_competingResource_exactlyOneWins() throws Exception {
        commanding("INC-CA", "alice");
        commanding("INC-CB", "bob");
        incidentService.createTask("INC-CA", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), false, null, "P1"));
        incidentService.createTask("INC-CB", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), false, null, "P2"));

        // 两个事件并发派工竞争同一资源 CRANE-1：全局资源锁串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> zones.dispatch("INC-CA", "alice",
                        new DispatchRequest(key(), List.of(
                                new DispatchItem("T-1", null, List.of("CRANE-1"))))),
                () -> zones.dispatch("INC-CB", "bob",
                        new DispatchRequest(key(), List.of(
                                new DispatchItem("T-1", null, List.of("CRANE-1")))))));

        long successes = results.stream().filter(DispatchView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        Object loser = results.stream().filter(ApiException.class::isInstance).findFirst()
                .orElseThrow();
        assertThat(((ApiException) loser).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // 恰好一条 ACTIVE 租约，失败方任务仍 OPEN 且无租约残留
        Integer activeLeases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class);
        assertThat(activeLeases).isEqualTo(1);
        Integer inProgress = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks WHERE status = 'IN_PROGRESS'", Integer.class);
        assertThat(inProgress).isEqualTo(1);
    }

    @Test
    void concurrentStart_sameTask_singleEffect() throws Exception {
        commanding("INC-CS", "alice");
        incidentService.createTask("INC-CS", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));

        List<Callable<TaskView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> zones.startTask("INC-CS", "T-1", "alice",
                    new TaskActionRequest(key())));
        }
        List<Object> results = runConcurrently(calls);

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(conflicts).isEqualTo(2);
        TaskView task = incidentService.getTask("INC-CS", "T-1");
        assertThat(task.status()).isEqualTo("IN_PROGRESS");
        assertThat(task.startedBy()).isEqualTo("alice");
    }
}
