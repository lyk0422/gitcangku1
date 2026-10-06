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

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskScheduleRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 执行时段安排并发测试：同人员跨事件并发安排由人员锁串行化（恰好一个成功）、
 * 同 requestId 并发单次生效、并发开始仅一个成功。真实 H2 事务验证最终数据，
 * 以 CountDownLatch 协调并发并设超时，不依赖打印或长时间休眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class TaskScheduleConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_task_schedules");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "t", List.of()));
    }

    private static TaskScheduleRequest scheduleReq(String requestId, int version, String assignee,
                                                   String start, String end) {
        return new TaskScheduleRequest(requestId, version, assignee,
                Instant.parse(start), Instant.parse(end));
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
    void concurrentSchedule_samePersonAcrossIncidents_oneWins() throws Exception {
        commanding("INC-CA", "alice");
        commanding("INC-CB", "alice");
        createTask("INC-CA", "alice", "T-1");
        createTask("INC-CB", "alice", "T-1");
        // 同人员同时段跨事件并发安排：人员锁串行化，恰好一个成功，另一个 422
        List<Object> results = runConcurrently(List.of(
                () -> service.scheduleTask("INC-CA", "T-1", "alice",
                        scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z",
                                "2026-10-06T11:00:00Z")),
                () -> service.scheduleTask("INC-CB", "T-1", "alice",
                        scheduleReq(key(), 1, "worker-1", "2026-10-06T10:30:00Z",
                                "2026-10-06T11:30:00Z"))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        long violations = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        assertThat(violations).isEqualTo(1);
        // 最终数据：仅一条计入约束的安排
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM incident_task_schedules"
                + " WHERE assignee = 'worker-1' AND status = 'SCHEDULED'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentSchedule_sameRequestId_singleEffect() throws Exception {
        commanding("INC-CI", "alice");
        createTask("INC-CI", "alice", "T-1");
        String requestId = key();
        TaskScheduleRequest req = scheduleReq(requestId, 1, "worker-1",
                "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z");
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.scheduleTask("INC-CI", "T-1", "alice", req));
        }
        List<Object> results = runConcurrently(tasks);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，版本只加一次，安排只有一条
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.getTask("INC-CI", "T-1").version()).isEqualTo(2);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_schedules", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentStart_singleSuccess() throws Exception {
        commanding("INC-CS", "alice");
        createTask("INC-CS", "alice", "T-1");
        service.scheduleTask("INC-CS", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z",
                        "2026-10-06T11:00:00Z"));
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T10:30:00Z"));
        // 并发开始（不同 requestId、同期望版本 2）：事件行锁串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.startTask("INC-CS", "T-1", "alice", new TaskStartRequest(key(), 2)),
                () -> service.startTask("INC-CS", "T-1", "alice", new TaskStartRequest(key(), 2))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(conflicts).isEqualTo(1);
        // 最终数据：版本只加一次，安排仅一条 STARTED
        assertThat(service.getTask("INC-CS", "T-1").version()).isEqualTo(3);
        Integer started = jdbc.queryForObject("SELECT COUNT(*) FROM incident_task_schedules"
                + " WHERE status = 'STARTED'", Integer.class);
        assertThat(started).isEqualTo(1);
    }
}
