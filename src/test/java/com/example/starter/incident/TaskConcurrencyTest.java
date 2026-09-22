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
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务并发边界测试：
 * 1) 任务新增与另一事件新增反向依赖并发时，环检测和写入串行一致，最终图无环；
 * 2) 任务完成与目标事件遏制并发时，按事务提交顺序形成合法结果；
 * 3) 并发同 commandKey 只产生一次业务效果。
 */
@SpringBootTest
class TaskConcurrencyTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blocks");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private String reportAndTakeover(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "事件 " + incidentKey, "r"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
        return incidentKey;
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
    void concurrentReverseEdges_exactlyOneWinsAndGraphAcyclic() throws Exception {
        String a = reportAndTakeover("INC-A", "alice");
        String c = reportAndTakeover("INC-C", "carol");

        // 两个并发创建：A -> C 与 C -> A，必须恰好一个因成环被 409 拒绝。
        List<Object> results = runConcurrently(List.of(
                () -> taskService.createTask(a, "alice",
                        new TaskCreateRequest(key(), "A1", "G", "A 依赖 C", List.of(c))),
                () -> taskService.createTask(c, "carol",
                        new TaskCreateRequest(key(), "C1", "G", "C 依赖 A", List.of(a)))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        long cycleConflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(cycleConflicts).isEqualTo(1);

        // 只有一个任务、一条边落库，且该图无环（再加任意反向边仍会被拒绝）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_tasks", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_task_blocks", Integer.class))
                .isEqualTo(1);

        boolean aTaskExists = taskService.listTasks(a).stream()
                .flatMap(g -> g.tasks().stream()).findAny().isPresent();
        String owner = aTaskExists ? a : c;
        String other = aTaskExists ? c : a;
        String otherCommander = aTaskExists ? "carol" : "alice";
        // 补建反向边仍必须失败
        ApiException ex = null;
        try {
            taskService.createTask(other, otherCommander,
                    new TaskCreateRequest(key(), "R1", "G", "反向", List.of(owner)));
        } catch (ApiException e) {
            ex = e;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.status().value()).isEqualTo(409);
    }

    @Test
    void completeTaskConcurrentlyWithTargetContainment_legalOutcome() throws Exception {
        String a = reportAndTakeover("INC-A", "alice");
        String b = reportAndTakeover("INC-B", "bob");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "A1", "G", "A 等 B 遏制", List.of(b)));

        List<Object> results = runConcurrently(List.of(
                () -> taskService.completeTask(a, "A1", "alice", new TaskActionRequest(key())),
                () -> incidentService.changeStatus(b, "bob",
                        new StatusRequest(key(), "CONTAINED"))));

        long taskSuccess = results.stream()
                .filter(r -> r instanceof TaskView t && "DONE".equals(t.status())).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();

        TaskView finalTask = taskService.getTask(a, "A1");
        if (taskSuccess == 1) {
            // 遏制先提交：完成成功
            assertThat(conflicts).isZero();
            assertThat(finalTask.status()).isEqualTo("DONE");
        } else {
            // 完成先提交：409，任务仍 OPEN；随后遏制已提交，重试完成必须成功
            assertThat(conflicts).isEqualTo(1);
            assertThat(finalTask.status()).isEqualTo("OPEN");
            assertThat(taskService.getTask(a, "A1").completable()).isTrue();
            assertThat(taskService.completeTask(a, "A1", "alice",
                    new TaskActionRequest(key())).status()).isEqualTo("DONE");
        }
    }

    @Test
    void resolveConcurrentlyWithLastTaskCompletion_legalOutcome() throws Exception {
        String a = reportAndTakeover("INC-A", "alice");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "A1", "G", "最后一个任务", List.of()));
        incidentService.changeStatus(a, "alice", new StatusRequest(key(), "CONTAINED"));

        List<Object> results = runConcurrently(List.of(
                () -> taskService.completeTask(a, "A1", "alice", new TaskActionRequest(key())),
                () -> incidentService.changeStatus(a, "alice",
                        new StatusRequest(key(), "RESOLVED"))));

        // 两个写都锁同一事件行，按提交顺序：完成先则解决成功；解决先则被门禁拒绝。
        long done = results.stream()
                .filter(r -> r instanceof TaskView t && "DONE".equals(t.status())).count();
        assertThat(done).isEqualTo(1);
        String finalStatus = incidentService.get(a).status();
        if ("RESOLVED".equals(finalStatus)) {
            assertThat(results).anyMatch(r -> r instanceof com.example.starter.incident.dto.Responses
                    .IncidentView v && "RESOLVED".equals(v.status()));
        } else {
            assertThat(finalStatus).isEqualTo("CONTAINED");
            // 门禁已随任务完成而放行
            assertThat(incidentService.changeStatus(a, "alice",
                    new StatusRequest(key(), "RESOLVED")).status()).isEqualTo("RESOLVED");
        }
    }

    @Test
    void concurrentSameCommandKey_singleTaskCreated() throws Exception {
        String a = reportAndTakeover("INC-A", "alice");
        String commandKey = key();
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> taskService.createTask(a, "alice",
                    new TaskCreateRequest(commandKey, "S1", "G", "同键并发", List.of())));
        }
        List<Object> results = runConcurrently(tasks);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).hasSize(3);
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_tasks", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey))
                .isEqualTo(1);
    }
}
