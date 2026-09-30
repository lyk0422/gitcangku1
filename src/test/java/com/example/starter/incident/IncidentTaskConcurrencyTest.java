package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskDispatchRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 处置任务并发边界测试：验证依赖图全局锁下并发反向依赖不成环、
 * 任务完成与目标事件遏制/当前事件解决按事务提交顺序形成合法结果、
 * 同 commandKey 并发单次生效及每事件 20 个任务上限的并发兜底。
 * 完成路径要求任务先派发并通过执行前检查进入 EXECUTING。
 */
@SpringBootTest
class IncidentTaskConcurrencyTest {

    private static final String RESOURCE = "RES-R";
    private static final String REGION = "A";
    private static final String CRED_TYPE = "TOKEN";

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
        jdbc.update("DELETE FROM task_dispatches");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private static String reqId() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void registerValidCredential(String actor) {
        service.registerCredential(actor, new CredentialRegisterRequest(key(), RESOURCE,
                CRED_TYPE, REGION, Instant.now().plus(1, ChronoUnit.HOURS)));
    }

    private TaskView readyToExecute(String incidentKey, String actor, String taskKey,
                                    List<String> blockers) {
        TaskView created = service.createTask(incidentKey, actor, new TaskCreateRequest(key(),
                taskKey, "G", "t", RESOURCE, REGION, blockers));
        service.dispatchTask(incidentKey, taskKey, actor,
                new TaskDispatchRequest(reqId(), RESOURCE, CRED_TYPE));
        TaskView executing = service.startTask(incidentKey, taskKey, actor,
                new TaskActionRequest(key()));
        assertThat(executing.status()).isEqualTo("EXECUTING");
        return created;
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

    private static long countConflicts(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
    }

    @Test
    void concurrentReverseDependencies_noCycle() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        // 并发创建 A→B 与 B→A 反向依赖：环检测与写入串行一致，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.createTask("INC-A", "alice", new TaskCreateRequest(key(), "T-1",
                        "G", "t", RESOURCE, REGION, List.of("INC-B"))),
                () -> service.createTask("INC-B", "bob", new TaskCreateRequest(key(), "T-1",
                        "G", "t", RESOURCE, REGION, List.of("INC-A")))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        // 最终图无环：全库至多一条边，且失败方不留任务
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
        Integer taskCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks", Integer.class);
        assertThat(taskCount).isEqualTo(1);
    }

    @Test
    void concurrentCompleteAndBlockerContain_commitOrderWins() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerValidCredential("alice");
        readyToExecute("INC-A", "alice", "T-1", List.of("INC-B"));

        // 并发：完成 A 的任务（依赖 B）与遏制 B
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key())),
                () -> service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"))));

        // 遏制必然成功；完成是否成功取决于其读取时 B 是否已提交遏制
        IncidentView blocker = service.get("INC-B");
        assertThat(blocker.status()).isEqualTo("CONTAINED");
        TaskView task = service.getTask("INC-A", "T-1");
        Object completeResult = results.get(0);
        if (task.status().equals("DONE")) {
            // 完成先看到已遏制的 B：成功，且阻塞确已解除
            assertThat(completeResult).isInstanceOf(TaskView.class);
        } else {
            // 完成先提交：B 尚未遏制，409 拒绝，任务仍 EXECUTING
            assertThat(task.status()).isEqualTo("EXECUTING");
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Test
    void concurrentCompleteAndResolve_gateConsistent() throws Exception {
        commanding("INC-A", "alice");
        registerValidCredential("alice");
        readyToExecute("INC-A", "alice", "T-1", List.of());
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "CONTAINED"));

        // 并发：完成唯一未终态任务与推进 RESOLVED（解决门禁要求无未终态任务）
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key())),
                () -> service.changeStatus("INC-A", "alice", new StatusRequest(key(), "RESOLVED"))));

        // 任务完成必然成功；解决仅在完成先提交时通过门禁
        TaskView task = service.getTask("INC-A", "T-1");
        assertThat(task.status()).isEqualTo("DONE");
        IncidentView end = service.get("INC-A");
        Object resolveResult = results.get(1);
        if ("RESOLVED".equals(end.status())) {
            assertThat(resolveResult).isInstanceOf(IncidentView.class);
        } else {
            // 解决先评估：任务仍 EXECUTING，门禁 409
            assertThat(end.status()).isEqualTo("CONTAINED");
            assertThat(resolveResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Test
    void concurrentCreate_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        String commandKey = key();
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.createTask("INC-A", "alice", new TaskCreateRequest(commandKey,
                    "T-1", "G", "t", RESOURCE, REGION, List.of())));
        }
        List<Object> results = runConcurrently(tasks);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且任务只创建一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.listTasks("INC-A").tasks()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentCreate_max20Enforced() throws Exception {
        commanding("INC-A", "alice");
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String taskKey = "T-" + i;
            tasks.add(() -> service.createTask("INC-A", "alice", new TaskCreateRequest(key(),
                    taskKey, "G", "任务" + taskKey, RESOURCE, REGION, List.of())));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(20);
        assertThat(countConflicts(results)).isEqualTo(5);
        assertThat(service.listTasks("INC-A").tasks()).hasSize(20);
    }
}
