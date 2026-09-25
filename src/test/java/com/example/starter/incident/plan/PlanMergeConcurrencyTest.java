package com.example.starter.incident.plan;

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

import com.example.starter.incident.ApiException;
import com.example.starter.incident.IncidentService;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.plan.dto.PlanRequests.BranchCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRequest;
import com.example.starter.incident.plan.dto.PlanRequests.MergeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanTaskInput;
import com.example.starter.incident.plan.dto.PlanRequests.PlanVersionCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.TaskStartRequest;
import com.example.starter.incident.plan.dto.PlanResponses.MergeView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanTaskView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanVersionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 方案合并并发边界测试：验证同一基准上的并发合并只有一个成功、
 * 合并与任务执行按事务提交顺序串行不产生混合版本、
 * 同 requestId 并发合并单次生效。
 */
@SpringBootTest
class PlanMergeConcurrencyTest {

    @Autowired
    private PlanService service;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM plan_merges");
        jdbc.update("DELETE FROM plan_task_executions");
        jdbc.update("DELETE FROM plan_edges");
        jdbc.update("DELETE FROM plan_tasks");
        jdbc.update("DELETE FROM plan_versions");
        jdbc.update("DELETE FROM command_keys");
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
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
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

    private PlanVersionView setupWithBranches(String incidentKey) {
        commanding(incidentKey, "alice");
        PlanVersionView base = service.createInitialVersion(incidentKey, "alice",
                new PlanVersionCreateRequest(key(),
                        List.of(new PlanTaskInput("A", "a", "u1"),
                                new PlanTaskInput("B", "b", null)), List.of()));
        PlanVersionView left = service.createBranch(incidentKey, "alice", base.id(),
                new BranchCreateRequest(key()));
        service.createBranch(incidentKey, "alice", base.id(), new BranchCreateRequest(key()));
        service.upsertDraftTask(incidentKey, "alice", left.id(), "A",
                new DraftTaskRequest("a-left", "u1"));
        return base;
    }

    private MergeView merge(String incidentKey, String requestId, String mergeKey,
                            long base, long left, long right, long leftExp, long rightExp) {
        return service.merge(incidentKey, "alice",
                new MergeRequest(requestId, mergeKey, base, left, right, leftExp, rightExp,
                        List.of()));
    }

    private List<PlanVersion> draftsOf(long baseVersionId) {
        return jdbc.query("SELECT id, status FROM plan_versions WHERE base_version_id = ?"
                        + " ORDER BY id",
                (rs, n) -> new PlanVersion(rs.getLong("id"), null, 0,
                        PlanVersionStatus.valueOf(rs.getString("status")), null, null, 0,
                        null, null, null, null, null), baseVersionId);
    }

    @Test
    void concurrentMerges_sameBase_singleWinner() throws Exception {
        PlanVersionView base = setupWithBranches("INC-C1");
        List<PlanVersion> drafts = draftsOf(base.id());
        long leftId = drafts.get(0).id();
        long rightId = drafts.get(1).id();

        // 两个不同 mergeKey 的合并同时提交：按提交顺序，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> merge("INC-C1", key(), "MG-C1a", base.id(), leftId, rightId, 2, 1),
                () -> merge("INC-C1", key(), "MG-C1b", base.id(), leftId, rightId, 2, 1)));

        long successes = results.stream().filter(MergeView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        // 只生成一个合并版本，两个分支都只被标记一次 MERGED
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions WHERE incident_key = 'INC-C1'"
                        + " AND status = 'PUBLISHED'", Integer.class);
        assertThat(published).isEqualTo(2); // 基准 + 一个合并结果
        Integer merged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions WHERE incident_key = 'INC-C1'"
                        + " AND status = 'MERGED'", Integer.class);
        assertThat(merged).isEqualTo(2);
        Integer mergeRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_merges WHERE incident_key = 'INC-C1'", Integer.class);
        assertThat(mergeRecords).isEqualTo(1);
    }

    @Test
    void concurrentMergeAndTaskExecution_serialized() throws Exception {
        PlanVersionView base = setupWithBranches("INC-C2");
        List<PlanVersion> drafts = draftsOf(base.id());
        long leftId = drafts.get(0).id();
        long rightId = drafts.get(1).id();

        // 并发：合并（左分支改了 A 标题、负责人不变）与开始执行 A
        List<Object> results = runConcurrently(List.of(
                () -> merge("INC-C2", key(), "MG-C2", base.id(), leftId, rightId, 2, 1),
                () -> service.startTask("INC-C2", "alice", "A", new TaskStartRequest(key()))));

        // 两者按事件行锁串行：合并看到执行态（负责人未变，合法）或执行落到新版本，均成功
        assertThat(results.get(0)).isInstanceOf(MergeView.class);
        assertThat(results.get(1)).isInstanceOf(PlanTaskView.class);
        PlanVersionView active = service.getActivePlan("INC-C2");
        assertThat(active.versionNo()).isEqualTo(4);
        PlanTaskView a = active.tasks().stream().filter(t -> t.taskId().equals("A"))
                .findFirst().orElseThrow();
        assertThat(a.status()).isEqualTo("IN_PROGRESS");
        assertThat(a.assignee()).isEqualTo("u1");
        assertThat(a.title()).isEqualTo("a-left");
    }

    @Test
    void concurrentSameRequestId_singleEffect() throws Exception {
        PlanVersionView base = setupWithBranches("INC-C3");
        List<PlanVersion> drafts = draftsOf(base.id());
        long leftId = drafts.get(0).id();
        long rightId = drafts.get(1).id();
        String requestId = key();

        List<Callable<MergeView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> merge("INC-C3", requestId, "MG-C3", base.id(), leftId, rightId,
                    2, 1));
        }
        List<Object> results = runConcurrently(calls);

        List<MergeView> successes = results.stream().filter(MergeView.class::isInstance)
                .map(MergeView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且只发布一个合并版本
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer mergeRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_merges WHERE merge_key = 'MG-C3'", Integer.class);
        assertThat(mergeRecords).isEqualTo(1);
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions WHERE incident_key = 'INC-C3'",
                Integer.class);
        assertThat(versions).isEqualTo(4);
    }
}
