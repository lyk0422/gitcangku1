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

import com.example.starter.incident.dto.Requests.AssignmentRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务执行安排并发边界测试：验证人员安排全局锁下并发重叠安排恰好一个成功、
 * 同 requestId 并发单次生效、同任务并发安排的版本条件更新兜底。
 */
@SpringBootTest
class TaskAssignmentConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM task_assignments");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static AssignmentRequest assignReq(String requestId, String assignee, String start,
                                               String end, long version) {
        return new AssignmentRequest(requestId, version, assignee,
                Instant.parse(start), Instant.parse(end));
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

    private static long countByStatus(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentOverlappingAssignments_exactlyOneSucceeds() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "bob", "T-1");
        // 同一人员跨事件并发重叠安排：全局锁串行化校验，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.scheduleAssignment("INC-A", "T-1", "alice",
                        assignReq(key(), "worker-1", "2026-09-22T10:00:00Z",
                                "2026-09-22T11:00:00Z", 1)),
                () -> service.scheduleAssignment("INC-B", "T-1", "bob",
                        assignReq(key(), "worker-1", "2026-09-22T10:30:00Z",
                                "2026-09-22T11:30:00Z", 1))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countByStatus(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
        // 最终只落一条安排
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestId_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        String requestId = key();
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                    assignReq(requestId, "worker-1", "2026-09-22T10:00:00Z",
                            "2026-09-22T11:00:00Z", 1)));
        }
        List<Object> results = runConcurrently(tasks);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，安排只创建一次，版本只递增一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Integer.class);
        assertThat(rows).isEqualTo(1);
        Integer keys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keys).isEqualTo(1);
    }

    @Test
    void concurrentSameTaskDifferentRequestId_versionGuardAllowsOne() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        // 同任务并发不同 requestId 的安排：版本条件更新兜底，恰好一个生效
        List<Object> results = runConcurrently(List.of(
                () -> service.scheduleAssignment("INC-A", "T-1", "alice",
                        assignReq(key(), "worker-1", "2026-09-22T10:00:00Z",
                                "2026-09-22T11:00:00Z", 1)),
                () -> service.scheduleAssignment("INC-A", "T-1", "alice",
                        assignReq(key(), "worker-2", "2026-09-22T12:00:00Z",
                                "2026-09-22T13:00:00Z", 1))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countByStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void concurrentDisjointAssignments_bothSucceed() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "bob", "T-1");
        // 不同人员互不冲突的安排并发均可成功
        List<Object> results = runConcurrently(List.of(
                () -> service.scheduleAssignment("INC-A", "T-1", "alice",
                        assignReq(key(), "worker-1", "2026-09-22T10:00:00Z",
                                "2026-09-22T11:00:00Z", 1)),
                () -> service.scheduleAssignment("INC-B", "T-1", "bob",
                        assignReq(key(), "worker-2", "2026-09-22T10:00:00Z",
                                "2026-09-22T11:00:00Z", 1))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(2);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Integer.class);
        assertThat(rows).isEqualTo(2);
    }
}
