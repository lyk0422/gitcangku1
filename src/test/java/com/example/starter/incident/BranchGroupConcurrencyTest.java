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
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.BranchGroupView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 互斥分支组并发边界测试：验证两人并发争选不同成员只有一份提交生效（其余 409）、
 * 同 commandKey 并发选择单次生效、并发同 groupKey 建组由唯一约束串行化，
 * 最终组状态、成员终态与版本在真实 H2 库中一致。
 */
@SpringBootTest
class BranchGroupConcurrencyTest {

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
        jdbc.update("DELETE FROM incident_task_branch_members");
        jdbc.update("DELETE FROM incident_task_branch_groups");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commandingWithTasks(String incidentKey, String commander, String... taskKeys) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
        for (String taskKey : taskKeys) {
            service.createTask(incidentKey, commander,
                    new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
        }
    }

    /**
     * 并发提交一批选择/建组请求并收集结果（成功值或异常）。
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
    void concurrentChooseDifferentMembers_singleCommit() throws Exception {
        commandingWithTasks("INC-A", "alice", "T1", "T2", "T3");
        service.createBranchGroup("INC-A", "alice",
                new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2", "T3")));

        // 两人并发争选不同成员：只有一份提交生效，其余 409
        List<Object> results = runConcurrently(List.of(
                () -> service.chooseBranch("INC-A", "BG-1", "alice",
                        new BranchGroupChooseRequest(key(), "T1", 1L)),
                () -> service.chooseBranch("INC-A", "BG-1", "alice",
                        new BranchGroupChooseRequest(key(), "T2", 1L))));

        long successes = results.stream().filter(BranchGroupView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);

        // 最终组 CHOSEN、版本 2，恰一个成员 DONE、其余 CANCELLED
        BranchGroupView group = service.getBranchGroup("INC-A", "BG-1");
        assertThat(group.status()).isEqualTo("CHOSEN");
        assertThat(group.version()).isEqualTo(2);
        assertThat(group.members())
                .filteredOn(m -> m.taskStatus().equals("DONE")).hasSize(1);
        assertThat(group.members())
                .filteredOn(m -> m.taskStatus().equals("CANCELLED")).hasSize(2);
        Integer chosenCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_branch_groups WHERE status = 'CHOSEN'",
                Integer.class);
        assertThat(chosenCount).isEqualTo(1);
    }

    @Test
    void concurrentChooseSameCommandKey_singleEffect() throws Exception {
        commandingWithTasks("INC-A", "alice", "T1", "T2");
        service.createBranchGroup("INC-A", "alice",
                new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2")));

        String commandKey = key();
        List<Callable<BranchGroupView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.chooseBranch("INC-A", "BG-1", "alice",
                    new BranchGroupChooseRequest(commandKey, "T1", 1L)));
        }
        List<Object> results = runConcurrently(tasks);

        List<BranchGroupView> successes = results.stream()
                .filter(BranchGroupView.class::isInstance)
                .map(BranchGroupView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且选择只生效一次（版本只加一）
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        BranchGroupView group = service.getBranchGroup("INC-A", "BG-1");
        assertThat(group.version()).isEqualTo(2);
        assertThat(group.chosenTaskKey()).isEqualTo("T1");
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentCreateSameGroupKey_singleGroup() throws Exception {
        commandingWithTasks("INC-A", "alice", "T1", "T2");

        // 并发同 groupKey 建组：唯一约束串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.createBranchGroup("INC-A", "alice",
                        new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2"))),
                () -> service.createBranchGroup("INC-A", "alice",
                        new BranchGroupCreateRequest(key(), "BG-1", List.of("T1", "T2")))));

        long successes = results.stream().filter(BranchGroupView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        assertThat(service.listBranchGroups("INC-A").groups()).hasSize(1);
        Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_branch_members", Integer.class);
        assertThat(memberCount).isEqualTo(2);
    }
}
