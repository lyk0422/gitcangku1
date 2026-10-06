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

import com.example.starter.incident.dto.Requests.ConfigureGroupsRequest;
import com.example.starter.incident.dto.Requests.GroupSpec;
import com.example.starter.incident.dto.Requests.MemberSpec;
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
 * 择一前置组并发边界测试：验证图锁下并发反向配置不成环、同 requestId 并发单次生效、
 * 并发配置按版本恰好一个成功、成员完成与目标任务完成并发时依赖图判定一致。
 */
@SpringBootTest
class IncidentTaskPrerequisiteConcurrencyTest {

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
        jdbc.update("DELETE FROM task_prerequisite_members");
        jdbc.update("DELETE FROM task_prerequisite_groups");
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

    private static MemberSpec member(String incidentKey, String taskKey, int version) {
        return new MemberSpec(incidentKey, taskKey, version);
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

    private static long countStatus(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentReverseConfigure_noCycle() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T1");
        createTask("INC-A", "alice", "X");
        createTask("INC-B", "bob", "T2");
        createTask("INC-B", "bob", "Y");

        // 并发反向配置：T1 前置 {T2,X} 与 T2 前置 {T1,Y}，图锁串行化后恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.configureGroups("INC-A", "T1", "alice",
                        new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                                member("INC-B", "T2", 1), member("INC-A", "X", 1)))))),
                () -> service.configureGroups("INC-B", "T2", "bob",
                        new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                                member("INC-A", "T1", 1), member("INC-B", "Y", 1))))))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        // 败者要么在图锁内检环得 422，要么因对方提交推进了成员版本得 409
        long failures = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT
                        || e.status() == HttpStatus.UNPROCESSABLE_ENTITY)
                .count();
        assertThat(failures).isEqualTo(1);
        // 最终任务依赖图无环：全库至多一组前置边
        Integer groupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_prerequisite_groups", Integer.class);
        assertThat(groupCount).isEqualTo(1);
        Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_prerequisite_members", Integer.class);
        assertThat(memberCount).isEqualTo(2);
    }

    @Test
    void concurrentConfigure_sameRequestId_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T");
        createTask("INC-A", "alice", "M1");
        createTask("INC-A", "alice", "M2");
        String requestId = key();
        List<Callable<TaskView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> service.configureGroups("INC-A", "T", "alice",
                    new ConfigureGroupsRequest(requestId, 1, List.of(new GroupSpec(List.of(
                            member("INC-A", "M1", 1), member("INC-A", "M2", 1)))))));
        }
        List<Object> results = runConcurrently(calls);

        List<TaskView> successes = results.stream().filter(TaskView.class::isInstance)
                .map(TaskView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，版本只推进一次，幂等键只占一行
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.getTask("INC-A", "T").version()).isEqualTo(2);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentConfigure_differentKeys_versionWins() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T");
        createTask("INC-A", "alice", "M1");
        createTask("INC-A", "alice", "M2");
        createTask("INC-A", "alice", "M3");
        createTask("INC-A", "alice", "M4");

        // 并发两个不同 requestId 的配置（同期望版本 1）：恰好一个成功，另一个版本 409
        List<Object> results = runConcurrently(List.of(
                () -> service.configureGroups("INC-A", "T", "alice",
                        new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                                member("INC-A", "M1", 1), member("INC-A", "M2", 1)))))),
                () -> service.configureGroups("INC-A", "T", "alice",
                        new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                                member("INC-A", "M3", 1), member("INC-A", "M4", 1))))))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        TaskView task = service.getTask("INC-A", "T");
        assertThat(task.version()).isEqualTo(2);
        assertThat(task.prerequisiteGroups()).hasSize(1);
        assertThat(task.prerequisiteGroups().get(0).members()).hasSize(2);
    }

    @Test
    void concurrentCompleteAndMemberComplete_gateConsistent() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-M", "mike");
        createTask("INC-A", "alice", "T");
        createTask("INC-A", "alice", "X");
        createTask("INC-M", "mike", "M");
        service.configureGroups("INC-A", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-M", "M", 1), member("INC-A", "X", 1))))));

        // 并发：完成 T（需要组内 DONE 成员）与完成成员 M
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-A", "T", "alice", new TaskActionRequest(key())),
                () -> service.completeTask("INC-M", "M", "mike", new TaskActionRequest(key()))));

        // 成员完成必然成功；T 的完成取决于其判定时 M 是否已提交 DONE
        assertThat(service.getTask("INC-M", "M").status()).isEqualTo("DONE");
        TaskView task = service.getTask("INC-A", "T");
        Object completeResult = results.get(0);
        if (task.status().equals("DONE")) {
            // 完成判定看到已 DONE 的 M：成功且冻结选中依据为 M
            assertThat(completeResult).isInstanceOf(TaskView.class);
            assertThat(task.prerequisiteGroups().get(0).selectedMember().taskKey()).isEqualTo("M");
        } else {
            // 完成先评估：组内无 DONE 成员，409 拒绝，任务仍 OPEN
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
    }
}
