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

import com.example.starter.incident.dto.Requests.BranchGroupChooseRequest;
import com.example.starter.incident.dto.Requests.BranchGroupCreateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.BranchGroupView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 分支组并发边界测试（真实 H2 事务）：并发争选不同成员只有一份提交、
 * 并发建组抢同一任务只有一个成功、整组选择与指挥交接/普通完成联合裁决。
 */
@SpringBootTest
class IncidentBranchGroupConcurrencyTest {

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
        jdbc.update("DELETE FROM incident_branch_group_members");
        jdbc.update("DELETE FROM incident_branch_groups");
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
                new TaskCreateRequest(key(), taskKey, "OP", "t", List.of()));
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
    void concurrentChooseDifferentMembers_singleCommit() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T1");
        createTask("INC-A", "alice", "T2");
        service.createBranchGroup("INC-A", "alice",
                new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2")));

        // 并发争选不同成员：只有一份提交，其余 409
        List<Object> results = runConcurrently(List.of(
                () -> service.chooseBranch("INC-A", "BG-1", "alice",
                        new BranchGroupChooseRequest(key(), "T1", 1L)),
                () -> service.chooseBranch("INC-A", "BG-1", "alice",
                        new BranchGroupChooseRequest(key(), "T2", 1L))));

        long successes = results.stream().filter(BranchGroupView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countByStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);

        // 终态一致：恰一个 DONE、另一个 CANCELLED，组 CHOSEN 且版本为 2
        BranchGroupView group = service.listBranchGroups("INC-A").groups().get(0);
        assertThat(group.status()).isEqualTo("CHOSEN");
        assertThat(group.version()).isEqualTo(2);
        long done = group.members().stream()
                .filter(m -> m.taskStatus().equals("DONE")).count();
        long cancelled = group.members().stream()
                .filter(m -> m.taskStatus().equals("CANCELLED")).count();
        assertThat(done).isEqualTo(1);
        assertThat(cancelled).isEqualTo(1);
        Integer chosenRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_branch_groups WHERE status = 'CHOSEN'"
                        + " AND version = 2", Integer.class);
        assertThat(chosenRows).isEqualTo(1);
    }

    @Test
    void concurrentCreateGroups_sharingTask_singleGroup() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T1");
        createTask("INC-A", "alice", "T2");
        createTask("INC-A", "alice", "T3");

        // 并发建组抢同一任务 T2：一个成功，另一个 422（成员已属另一组）
        List<Object> results = runConcurrently(List.of(
                () -> service.createBranchGroup("INC-A", "alice",
                        new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2"))),
                () -> service.createBranchGroup("INC-A", "alice",
                        new BranchGroupCreateRequest(key(), "BG-2", List.of("T2", "T3")))));

        long successes = results.stream().filter(BranchGroupView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countByStatus(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
        assertThat(service.listBranchGroups("INC-A").groups()).hasSize(1);
        Integer memberRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_branch_group_members", Integer.class);
        assertThat(memberRows).isEqualTo(2);
    }

    @Test
    void concurrentChooseAndNormalComplete_chooseWins() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T1");
        createTask("INC-A", "alice", "T2");
        service.createBranchGroup("INC-A", "alice",
                new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2")));

        // 并发：整组选择与普通完成同组成员（普通入口必须被拦截）
        List<Object> results = runConcurrently(List.of(
                () -> service.chooseBranch("INC-A", "BG-1", "alice",
                        new BranchGroupChooseRequest(key(), "T1", 1L)),
                () -> service.completeTask("INC-A", "T1", "alice",
                        new TaskActionRequest(key()))));

        assertThat(results.get(0)).isInstanceOf(BranchGroupView.class);
        assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        BranchGroupView group = service.listBranchGroups("INC-A").groups().get(0);
        assertThat(group.status()).isEqualTo("CHOSEN");
        assertThat(group.members().get(0).taskStatus()).isEqualTo("DONE");
        assertThat(group.members().get(1).taskStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void concurrentChooseAndHandover_currentCommanderArbitrates() throws Exception {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T1");
        createTask("INC-A", "alice", "T2");
        service.createBranchGroup("INC-A", "alice",
                new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2")));
        // 交接给 bob 并被接受：此后只有 bob 是合法选择人
        service.initiateTransfer("INC-A", "alice",
                new com.example.starter.incident.dto.Requests.TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-A", "bob",
                new com.example.starter.incident.dto.Requests.TransferAcceptRequest(key()));

        // 并发：旧指挥人 alice 与新指挥人 bob 争选 → 只有 bob 能提交
        List<Object> results = runConcurrently(List.of(
                () -> service.chooseBranch("INC-A", "BG-1", "alice",
                        new BranchGroupChooseRequest(key(), "T1", 1L)),
                () -> service.chooseBranch("INC-A", "BG-1", "bob",
                        new BranchGroupChooseRequest(key(), "T2", 1L))));

        assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(results.get(1)).isInstanceOf(BranchGroupView.class);
        BranchGroupView group = service.listBranchGroups("INC-A").groups().get(0);
        assertThat(group.chosenTaskKey()).isEqualTo("T2");
        assertThat(group.chosenBy()).isEqualTo("bob");
    }
}
