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
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanEdgeInput;
import com.example.starter.incident.dto.Requests.PlanMergeRequest;
import com.example.starter.incident.dto.Requests.PlanTaskActionRequest;
import com.example.starter.incident.dto.Requests.PlanTaskInput;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.RevisionCreateRequest;
import com.example.starter.incident.dto.Requests.RevisionUpdateRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.MergeEvidenceView;
import com.example.starter.incident.dto.Responses.PlanTaskView;
import com.example.starter.incident.dto.Responses.PlanVersionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 方案合并并发边界测试（真实 H2 库）：验证方案行锁下合并与合并、合并与任务执行、
 * 合并与草稿变更按事务提交顺序串行生效，不发布混合版本；同 requestId 并发单次生效。
 */
@SpringBootTest
class PlanMergeConcurrencyTest {

    @Autowired
    private PlanService planService;

    @Autowired
    private PlanMergeService mergeService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM plan_merges");
        jdbc.update("DELETE FROM plan_edges");
        jdbc.update("DELETE FROM plan_tasks");
        jdbc.update("DELETE FROM plan_versions");
        jdbc.update("DELETE FROM plans");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static PlanTaskInput task(String taskId, String incidentKey, String title,
                                      String assignee) {
        return new PlanTaskInput(taskId, incidentKey, "G1", title, assignee, "PENDING",
                null, null);
    }

    private int createRevision(String planKey, String actor, int baseVersion) {
        return (int) planService.createRevision(planKey, actor,
                new RevisionCreateRequest((long) baseVersion)).versionNo();
    }

    private void updateRevision(String planKey, int versionNo, String actor, long expected,
                                List<PlanTaskInput> tasks, List<PlanEdgeInput> edges) {
        planService.updateRevision(planKey, versionNo, actor,
                new RevisionUpdateRequest(expected, tasks, edges));
    }

    private static PlanMergeRequest mergeBody(String requestId, String mergeKey, long base,
                                              long left, long right, long leftExpected,
                                              long rightExpected) {
        return new PlanMergeRequest(requestId, mergeKey, base, left, right,
                leftExpected, rightExpected, List.of());
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

    /**
     * 准备：事件 INC-A（alice 指挥）+ 方案 + 两个草稿，左侧加任务 T-1。
     * 返回 [leftVersionNo, rightVersionNo]。
     */
    private int[] preparePlanWithDrafts(String planKey) {
        planService.createPlan("alice", new PlanCreateRequest(planKey));
        int left = createRevision(planKey, "alice", 1);
        int right = createRevision(planKey, "alice", 1);
        updateRevision(planKey, left, "alice", 1,
                List.of(task("T-1", "INC-A", "t", "alice")), List.of());
        return new int[]{left, right};
    }

    @Test
    void concurrentMergesSameBranches_singlePublished() throws Exception {
        commanding("INC-A", "alice");
        int[] drafts = preparePlanWithDrafts("PLAN-C1");

        List<Object> results = runConcurrently(List.of(
                () -> mergeService.merge("PLAN-C1", "alice",
                        mergeBody("REQ-C1A", "MG-C1A", 1, drafts[0], drafts[1], 2, 1)),
                () -> mergeService.merge("PLAN-C1", "alice",
                        mergeBody("REQ-C1B", "MG-C1B", 1, drafts[0], drafts[1], 2, 1))));

        long successes = results.stream().filter(MergeEvidenceView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(conflicts).isEqualTo(1);
        // 只发布一个合并版本：共 4 个版本（初始 + 两草稿 + 合并），证据仅一条，活动版本为 4
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions", Integer.class);
        assertThat(versionCount).isEqualTo(4);
        Integer mergeCount = jdbc.queryForObject("SELECT COUNT(*) FROM plan_merges", Integer.class);
        assertThat(mergeCount).isEqualTo(1);
        assertThat(planService.getPlan("PLAN-C1").activeVersion()).isEqualTo(4);
        // 两分支均已 MERGED
        assertThat(planService.getVersion("PLAN-C1", drafts[0]).status()).isEqualTo("MERGED");
        assertThat(planService.getVersion("PLAN-C1", drafts[1]).status()).isEqualTo("MERGED");
    }

    @Test
    void concurrentSameRequestId_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        int[] drafts = preparePlanWithDrafts("PLAN-C2");
        PlanMergeRequest request = mergeBody("REQ-C2", "MG-C2", 1, drafts[0], drafts[1], 2, 1);

        List<Callable<MergeEvidenceView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> mergeService.merge("PLAN-C2", "alice", request));
        }
        List<Object> results = runConcurrently(calls);

        List<MergeEvidenceView> successes = results.stream()
                .filter(MergeEvidenceView.class::isInstance)
                .map(MergeEvidenceView.class::cast).toList();
        // 并发同键：串行化后同参重放，全部返回首次快照
        assertThat(successes).hasSize(3);
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer mergeCount = jdbc.queryForObject("SELECT COUNT(*) FROM plan_merges", Integer.class);
        assertThat(mergeCount).isEqualTo(1);
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions", Integer.class);
        assertThat(versionCount).isEqualTo(4);
    }

    @Test
    void concurrentMergeAndTaskExecution_commitOrderWins() throws Exception {
        commanding("INC-A", "alice");
        int[] drafts = preparePlanWithDrafts("PLAN-C3");
        // 第一阶段合并：发布 T-1（PENDING）为活动版本 4
        mergeService.merge("PLAN-C3", "alice",
                mergeBody("REQ-C3A", "MG-C3A", 1, drafts[0], drafts[1], 2, 1));
        // 第二阶段草稿（T-1 仍为 PENDING 快照）
        int left2 = createRevision("PLAN-C3", "alice", 4);
        int right2 = createRevision("PLAN-C3", "alice", 4);

        // 并发：开始执行 T-1 与合并旧快照（会回退 IN_PROGRESS 状态）
        List<Object> results = runConcurrently(List.of(
                () -> planService.startTask("PLAN-C3", "T-1", "alice",
                        new PlanTaskActionRequest(key())),
                () -> mergeService.merge("PLAN-C3", "alice",
                        mergeBody("REQ-C3B", "MG-C3B", 4, left2, right2, 1, 1))));

        Object startResult = results.get(0);
        Object mergeResult = results.get(1);
        // 任务开始必然成功（无论落在哪个活动版本上）
        assertThat(startResult).isInstanceOf(PlanTaskView.class);
        assertThat(((PlanTaskView) startResult).status()).isEqualTo("IN_PROGRESS");
        long activeVersion = planService.getPlan("PLAN-C3").activeVersion();
        if (mergeResult instanceof ApiException exception) {
            // 执行先提交：合并检测到状态回退，整次 422，活动版本仍为 4，不生成合并版本
            assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(activeVersion).isEqualTo(4);
            Integer mergeCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM plan_merges", Integer.class);
            assertThat(mergeCount).isEqualTo(1);
        } else {
            // 合并先提交：新活动版本 7，执行落在其上，无混合版本
            assertThat(mergeResult).isInstanceOf(MergeEvidenceView.class);
            assertThat(activeVersion).isEqualTo(7);
            PlanVersionView active = planService.getVersion("PLAN-C3", 7);
            assertThat(active.tasks()).hasSize(1);
            assertThat(active.tasks().get(0).status()).isEqualTo("IN_PROGRESS");
        }
    }

    @Test
    void concurrentDraftUpdateAndMerge_serialized() throws Exception {
        commanding("INC-A", "alice");
        int[] drafts = preparePlanWithDrafts("PLAN-C4");
        // 左侧草稿已更新一次（expectedVersion = 2），右侧未更新（expectedVersion = 1）

        // 并发：再次变更左侧草稿（expected 2→3）与按 expected=2 提交合并
        List<Object> results = runConcurrently(List.of(
                () -> {
                    updateRevision("PLAN-C4", drafts[0], "alice", 2,
                            List.of(task("T-1", "INC-A", "t3", "alice")), List.of());
                    return "UPDATED";
                },
                () -> mergeService.merge("PLAN-C4", "alice",
                        mergeBody("REQ-C4", "MG-C4", 1, drafts[0], drafts[1], 2, 1))));

        long updates = results.stream().filter("UPDATED"::equals).count();
        long merges = results.stream().filter(MergeEvidenceView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        // 按提交顺序恰好一个成功：草稿变更先则合并 409（分支已变化），合并先则草稿已 MERGED 变更 409
        assertThat(updates + merges).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        if (merges == 1) {
            assertThat(planService.getVersion("PLAN-C4", drafts[0]).status()).isEqualTo("MERGED");
        } else {
            assertThat(planService.getVersion("PLAN-C4", drafts[0]).status()).isEqualTo("DRAFT");
            assertThat(planService.getVersion("PLAN-C4", drafts[0]).expectedVersion()).isEqualTo(3);
        }
    }
}
