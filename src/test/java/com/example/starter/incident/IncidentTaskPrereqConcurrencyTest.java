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

import com.example.starter.incident.dto.Requests.PrereqConfigureRequest;
import com.example.starter.incident.dto.Requests.PrereqMemberRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 择一前置组并发边界测试：并发配置版本竞争恰一个成功、同 requestId 并发单次生效、
 * 成员完成与当前任务完成按事务提交顺序形成合法结果、配置与成员完成串行一致。
 */
@SpringBootTest
class IncidentTaskPrereqConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM task_prereq_snapshot");
        jdbc.update("DELETE FROM task_prereq_members");
        jdbc.update("DELETE FROM task_prereq_groups");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
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

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static PrereqMemberRequest member(String incidentKey, String taskKey, int version) {
        return new PrereqMemberRequest(incidentKey, taskKey, version);
    }

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
    void concurrentConfigure_sameTask_singleSuccess() throws Exception {
        commanding("INC-C1", "alice");
        createTask("INC-C1", "alice", "T");
        createTask("INC-C1", "alice", "A");
        createTask("INC-C1", "alice", "B");
        // 两个并发配置都期望版本 1：恰一个成功，另一个旧版本 409
        List<Object> results = runConcurrently(List.of(
                () -> service.configurePrerequisites("INC-C1", "T", "alice",
                        new PrereqConfigureRequest(key(), 1, List.of(List.of(
                                member("INC-C1", "A", 1), member("INC-C1", "B", 1))))),
                () -> service.configurePrerequisites("INC-C1", "T", "alice",
                        new PrereqConfigureRequest(key(), 1, List.of(List.of(
                                member("INC-C1", "B", 1), member("INC-C1", "A", 1)))))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        TaskView end = service.getTask("INC-C1", "T");
        assertThat(end.version()).isEqualTo(2);
        assertThat(end.prerequisiteGroups()).hasSize(1);
        Integer groupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_prereq_groups", Integer.class);
        assertThat(groupCount).isEqualTo(1);
    }

    @Test
    void concurrentConfigure_sameRequestId_singleEffect() throws Exception {
        commanding("INC-C2", "alice");
        createTask("INC-C2", "alice", "T");
        createTask("INC-C2", "alice", "A");
        createTask("INC-C2", "alice", "B");
        String requestId = key();
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.configurePrerequisites("INC-C2", "T", "alice",
                    new PrereqConfigureRequest(requestId, 1, List.of(List.of(
                            member("INC-C2", "A", 1), member("INC-C2", "B", 1))))));
        }
        List<Object> results = runConcurrently(tasks);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        // 版本只递增一次，组只写一次，幂等键只有一行
        assertThat(service.getTask("INC-C2", "T").version()).isEqualTo(2);
        Integer groupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_prereq_groups", Integer.class);
        assertThat(groupCount).isEqualTo(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentMemberCompleteAndTaskComplete_commitOrderWins() throws Exception {
        commanding("INC-C3", "alice");
        commanding("INC-M3", "bob");
        createTask("INC-C3", "alice", "T");
        createTask("INC-M3", "bob", "A");
        createTask("INC-M3", "bob", "B");
        service.configurePrerequisites("INC-C3", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-M3", "A", 1), member("INC-M3", "B", 1)))));

        // 并发：完成成员 A 与完成任务 T（T 的组判定需要至少一个 DONE 成员）
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-M3", "A", "bob", new TaskActionRequest(key())),
                () -> service.completeTask("INC-C3", "T", "alice", new TaskActionRequest(key()))));

        // 成员完成必然成功；T 是否完成取决于其判定时 A 是否已提交 DONE
        assertThat(service.getTask("INC-M3", "A").status()).isEqualTo("DONE");
        TaskView t = service.getTask("INC-C3", "T");
        Object completeT = results.get(1);
        if (t.status().equals("DONE")) {
            assertThat(completeT).isInstanceOf(TaskView.class);
            assertThat(t.completionSnapshot().get(0).selectedBasis()).isEqualTo("INC-M3/A");
        } else {
            assertThat(t.status()).isEqualTo("OPEN");
            assertThat(completeT).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
        // 另一候选 B 保持 OPEN，不被自动取消
        assertThat(service.getTask("INC-M3", "B").status()).isEqualTo("OPEN");
    }

    @Test
    void concurrentConfigureAndMemberComplete_serialized() throws Exception {
        commanding("INC-C4", "alice");
        commanding("INC-M4", "bob");
        createTask("INC-C4", "alice", "T");
        createTask("INC-M4", "bob", "A");
        createTask("INC-M4", "bob", "B");

        // 并发：配置 T（期望成员 A 版本 1）与完成成员 A（版本 1 → 2）
        List<Object> results = runConcurrently(List.of(
                () -> service.configurePrerequisites("INC-C4", "T", "alice",
                        new PrereqConfigureRequest(key(), 1, List.of(List.of(
                                member("INC-M4", "A", 1), member("INC-M4", "B", 1))))),
                () -> service.completeTask("INC-M4", "A", "bob", new TaskActionRequest(key()))));

        // 成员完成必然成功；配置仅在先于完成提交时通过成员版本校验
        TaskView a = service.getTask("INC-M4", "A");
        assertThat(a.status()).isEqualTo("DONE");
        assertThat(a.version()).isEqualTo(2);
        Object configure = results.get(0);
        TaskView t = service.getTask("INC-C4", "T");
        if (configure instanceof TaskView) {
            assertThat(t.version()).isEqualTo(2);
            assertThat(t.prerequisiteGroups()).hasSize(1);
        } else {
            assertThat(configure).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(t.version()).isEqualTo(1);
            assertThat(t.prerequisiteGroups()).isEmpty();
        }
    }
}
