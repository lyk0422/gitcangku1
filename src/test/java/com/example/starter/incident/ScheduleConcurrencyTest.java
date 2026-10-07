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

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ScheduleProposeRequest;
import com.example.starter.incident.dto.Requests.ScheduleTaskRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.ScheduleHistoryView;
import com.example.starter.incident.dto.Responses.ScheduleView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 整案排程提案并发测试：提交与任务完成并发时形成合法串行结果
 * （不会保存混合状态的 CURRENT 方案）；同 requestId 并发单次生效；
 * 不同键并发提交版本串行递增。
 */
@SpringBootTest
class ScheduleConcurrencyTest {

    private static final Instant BASE = Instant.parse("2026-10-01T01:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_schedules");
        jdbc.update("DELETE FROM incident_schedule_state");
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

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void openTask(String incidentKey, String actor, String taskKey) {
        incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static ScheduleProposeRequest propose(String requestId, String taskKey) {
        return new ScheduleProposeRequest(requestId, BASE, null,
                List.of(new ScheduleTaskRequest(taskKey, 5L, 0L, 10L)), List.of());
    }

    /**
     * 并发提交一批操作并收集结果（成功值或异常），设超时防止挂起。
     */
    private static <T> List<Object> runConcurrently(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return call.call();
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
    void concurrentProposeAndComplete_serializedNoMixedCurrent() throws Exception {
        commanding("INC-C1", "alice");
        openTask("INC-C1", "alice", "T-1");
        // 并发：提交引用 T-1 的排程提案 vs 完成 T-1
        List<Object> results = runConcurrently(List.of(
                () -> scheduleService.propose("INC-C1", "alice", propose(key(), "T-1")),
                () -> incidentService.completeTask("INC-C1", "T-1", "alice",
                        new TaskActionRequest(key()))));

        // 任务完成必然成功（无阻塞）；提案结果取决于串行顺序
        Object proposeResult = results.get(0);
        ScheduleHistoryView history = scheduleService.listSchedules("INC-C1");
        if (proposeResult instanceof ScheduleView) {
            // 提案先提交：任务完成后事件版本推进，方案必须已 STALE，不留混合状态 CURRENT
            assertThat(history.history()).hasSize(1);
            assertThat(history.current().status()).isEqualTo("STALE");
            assertThat(history.current().tasks().get(0).taskStatus()).isEqualTo("OPEN");
        } else {
            // 完成先提交：提案引用非 OPEN 任务，409 且不留方案
            assertThat(proposeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(history.history()).isEmpty();
        }
        assertThat(incidentService.getTask("INC-C1", "T-1").status()).isEqualTo("DONE");
        Integer currentCount = jdbc.queryForObject("SELECT COUNT(*) FROM incident_schedules"
                + " WHERE status = 'CURRENT'", Integer.class);
        assertThat(currentCount).isZero();
    }

    @Test
    void concurrentPropose_sameRequestId_singleEffect() throws Exception {
        commanding("INC-C2", "alice");
        openTask("INC-C2", "alice", "T-1");
        String requestId = key();
        List<Callable<ScheduleView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> scheduleService.propose("INC-C2", "alice",
                    propose(requestId, "T-1")));
        }
        List<Object> results = runConcurrently(calls);

        List<ScheduleView> successes = results.stream().filter(ScheduleView.class::isInstance)
                .map(ScheduleView.class::cast).toList();
        // 同键同参：全部返回首次完整结果，不出现唯一键 500
        assertThat(successes).hasSize(3);
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(scheduleService.listSchedules("INC-C2").history()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentPropose_differentKeys_versionSerialized() throws Exception {
        commanding("INC-C3", "alice");
        openTask("INC-C3", "alice", "T-1");
        // 两个并发首版提案（均未携带期望版本）：事件行锁串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> scheduleService.propose("INC-C3", "alice", propose(key(), "T-1")),
                () -> scheduleService.propose("INC-C3", "alice", propose(key(), "T-1"))));

        long successes = results.stream().filter(ScheduleView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        ScheduleHistoryView history = scheduleService.listSchedules("INC-C3");
        assertThat(history.history()).hasSize(1);
        assertThat(history.current().scheduleVersion()).isEqualTo(1);
        assertThat(history.current().status()).isEqualTo("CURRENT");
    }
}
