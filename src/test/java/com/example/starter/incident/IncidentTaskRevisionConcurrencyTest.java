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

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskBlockersReplaceRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务依赖修订并发边界测试：验证并发反向修订/建边最终图无环、
 * 同任务并发修订按版本串行、修订与完成/取消按提交顺序判定、
 * 同 commandKey 并发单次生效。
 */
@SpringBootTest
class IncidentTaskRevisionConcurrencyTest {

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
        jdbc.update("DELETE FROM incident_task_revisions");
        jdbc.update("DELETE FROM incident_task_blockers");
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
    void concurrentReverseRevisions_noCycle() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));

        // 并发反向修订 A→B 与 B→A：环检测与边写入串行一致，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                        new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B"))),
                () -> service.replaceTaskBlockers("INC-B", "T-1", "bob",
                        new TaskBlockersReplaceRequest(key(), 1, List.of("INC-A")))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        // 最终图无环：全库至多一条边；失败方版本与历史不变
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
        Integer revisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_revisions", Integer.class);
        assertThat(revisionCount).isEqualTo(1);
        assertThat(service.getTask("INC-A", "T-1").version()
                + service.getTask("INC-B", "T-1").version()).isEqualTo(3);
    }

    @Test
    void concurrentRevisionAndReverseCreate_noCycle() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));

        // 并发：修订 A→B 与在 B 上新建 B→A 的任务，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                        new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B"))),
                () -> service.createTask("INC-B", "bob",
                        new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-A")))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void concurrentRevisions_sameTask_versionSerializes() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));

        // 同一任务并发修订且都基于版本 1：事件行锁串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                        new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B"))),
                () -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                        new TaskBlockersReplaceRequest(key(), 1, List.of("INC-C")))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        // 版本只加一次，历史只有一条
        TaskView task = service.getTask("INC-A", "T-1");
        assertThat(task.version()).isEqualTo(2);
        assertThat(service.taskRevisions("INC-A", "T-1").revisions()).hasSize(1);
    }

    @Test
    void concurrentRevisionAndComplete_commitOrderWins() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));

        // 并发：修订为依赖 INC-B（未解除）与完成任务，按提交顺序判定
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                        new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B"))),
                () -> service.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()))));

        // 恰好一个成功：完成先提交则修订遇终态 409；修订先提交则完成遇未解除阻塞 409
        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        TaskView task = service.getTask("INC-A", "T-1");
        // 无论谁先提交，版本都只加一
        assertThat(task.version()).isEqualTo(2);
        if (task.status().equals("DONE")) {
            // 完成先提交：任务无依赖（修订未生效）
            assertThat(task.blockers()).isEmpty();
            assertThat(service.taskRevisions("INC-A", "T-1").revisions().get(0).operation())
                    .isEqualTo("COMPLETE");
        } else {
            // 修订先提交：任务仍 OPEN 且依赖 INC-B
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(task.blockers()).extracting(TaskBlockerView::incidentKey)
                    .containsExactly("INC-B");
            assertThat(service.taskRevisions("INC-A", "T-1").revisions().get(0).operation())
                    .isEqualTo("REPLACE");
        }
    }

    @Test
    void concurrentRevision_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        String commandKey = key();
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                    new TaskBlockersReplaceRequest(commandKey, 1, List.of("INC-B"))));
        }
        List<Object> results = runConcurrently(tasks);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，替换只生效一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        assertThat(service.taskRevisions("INC-A", "T-1").revisions()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }
}
