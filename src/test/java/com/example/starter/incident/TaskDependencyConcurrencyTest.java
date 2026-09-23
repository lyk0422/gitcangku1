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
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskDependencyReplaceRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 阻塞列表替换的并发边界测试：
 * 与其他事件反向建边/另一任务修订并发时图必须无环；同任务并发修订按 expectedTaskVersion
 * 恰好一个成功；与完成、取消、指挥交接竞争时按提交顺序校验指挥人、状态与版本。
 */
@SpringBootTest
class TaskDependencyConcurrencyTest {

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
        jdbc.update("DELETE FROM incident_task_dependency_revisions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发修订场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String commander, String taskKey,
                            List<String> blockers) {
        service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, blockers));
    }

    private static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
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

    private static boolean isConflict(Object result) {
        return result instanceof ApiException e && e.status() == HttpStatus.CONFLICT;
    }

    @Test
    void concurrentReverseReplacements_noCycle() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "TA", List.of());
        createTask("INC-B", "bob", "TB", List.of());
        // 并发把 A 的任务改为依赖 B、B 的任务改为依赖 A：恰好一个成功，最终图无环
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskDependencies("INC-A", "TA", "alice",
                        new TaskDependencyReplaceRequest(key(), 1, List.of("INC-B"))),
                () -> service.replaceTaskDependencies("INC-B", "TB", "bob",
                        new TaskDependencyReplaceRequest(key(), 1, List.of("INC-A")))));
        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(results).anyMatch(TaskDependencyConcurrencyTest::isConflict);
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
        // 仅成功方版本推进到 2
        Integer versionSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(version),0) FROM incident_tasks", Integer.class);
        assertThat(versionSum).isEqualTo(3);
    }

    @Test
    void concurrentReplaceOnSameTask_singleSuccessByVersion() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        // 两个修订都基于版本 1 并发提交：恰好一个成功，另一个 409，版本只加一
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskDependencies("INC-A", "T-1", "alice",
                        new TaskDependencyReplaceRequest(key(), 1, List.of())),
                () -> service.replaceTaskDependencies("INC-A", "T-1", "alice",
                        new TaskDependencyReplaceRequest(key(), 1, List.of()))));
        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(TaskDependencyConcurrencyTest::isConflict);
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        assertThat(service.taskRevisions("INC-A", "T-1").revisions()).hasSize(1);
    }

    @Test
    void concurrentReplaceAndComplete_commitOrderWins() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        // 并发：把依赖替换为一个仍 OPEN 的新阻塞，与完成任务竞争
        commanding("INC-B", "bob");
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskDependencies("INC-A", "T-1", "alice",
                        new TaskDependencyReplaceRequest(key(), 1, List.of("INC-B"))),
                () -> service.completeTask("INC-A", "T-1", "alice",
                        new TaskActionRequest(key()))));
        TaskView task = service.getTask("INC-A", "T-1");
        Object replaceResult = results.get(0);
        Object completeResult = results.get(1);
        if ("DONE".equals(task.status())) {
            // 完成先提交（修订随后基于已终态任务评估，被拒绝）
            assertThat(completeResult).isInstanceOf(TaskView.class);
            assertThat(isConflict(replaceResult)).isTrue();
            // 终态任务不得修订：依赖保持空
            assertThat(task.blockers()).isEmpty();
            assertThat(task.version()).isEqualTo(2);
            assertThat(service.taskRevisions("INC-A", "T-1").revisions()).isEmpty();
        } else {
            // 修订先提交（版本 2、依赖 INC-B），完成随后看到未解除阻塞，409，任务仍 OPEN
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(task.version()).isEqualTo(2);
            assertThat(task.blockers()).extracting(b -> b.incidentKey())
                    .containsExactly("INC-B");
            assertThat(replaceResult).isInstanceOf(TaskView.class);
            assertThat(isConflict(completeResult)).isTrue();
        }
    }

    @Test
    void concurrentReplaceAndTransfer_actorCheckedAtCommit() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        service.initiateTransfer("INC-A", "alice", new TransferRequest(key(), "bob"));
        // 并发：alice 提交修订 与 bob 接受交接
        List<Object> results = runConcurrently(List.of(
                () -> service.replaceTaskDependencies("INC-A", "T-1", "alice",
                        new TaskDependencyReplaceRequest(key(), 1, List.of())),
                () -> service.acceptTransfer("INC-A", "bob", new TransferAcceptRequest(key()))));
        TaskView task = service.getTask("INC-A", "T-1");
        Object replaceResult = results.get(0);
        // 交接必然成功；两个写操作均先锁事件行，按提交顺序判定当前指挥人
        assertThat(service.get("INC-A").commander()).isEqualTo("bob");
        if (task.version() == 2) {
            // 修订先提交（alice 当时仍是指挥人）：修订成功，随后交接生效
            assertThat(replaceResult).isInstanceOf(TaskView.class);
            assertThat(service.taskRevisions("INC-A", "T-1").revisions()).hasSize(1);
        } else {
            // 交接先提交：alice 失去指挥权，修订 409，版本与历史不变
            assertThat(task.version()).isEqualTo(1);
            assertThat(isConflict(replaceResult)).isTrue();
            assertThat(service.taskRevisions("INC-A", "T-1").revisions()).isEmpty();
        }
    }
}
