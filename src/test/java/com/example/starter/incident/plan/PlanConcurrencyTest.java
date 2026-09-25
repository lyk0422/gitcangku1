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
import com.example.starter.incident.plan.dto.PlanRequests.DraftCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRequest;
import com.example.starter.incident.plan.dto.PlanRequests.MergeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanTaskActionRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanTaskInput;
import com.example.starter.incident.plan.dto.PlanResponses.MergeResultView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanTaskView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanVersionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 方案合并并发边界测试（真实 H2）：合并与合并、合并与任务执行、合并与草稿编辑、
 * 同 requestId 并发重放，均按事务提交顺序生效，不发布混合版本。
 */
@SpringBootTest
class PlanConcurrencyTest {

    @Autowired
    private PlanService plans;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM plan_merges");
        jdbc.update("DELETE FROM plan_task_state");
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
        incidents.report(new ReportRequest(incidentKey, "S2", "并发场景", "r"));
        incidents.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createPlan(String ik, List<PlanTaskInput> tasks) {
        plans.createInitialPlan(ik, "alice", new PlanCreateRequest(key(), tasks, List.of()));
    }

    private static PlanTaskInput task(String taskId, String title, String assignee) {
        return new PlanTaskInput(taskId, "G", title, assignee);
    }

    private static MergeRequest mergeReq(String requestId, String mergeKey, int base, int left,
                                         int right, int leftExpected, int rightExpected) {
        return new MergeRequest(requestId, mergeKey, base, left, right, leftExpected,
                rightExpected, List.of());
    }

    /**
     * 并发提交一批任务并收集结果（成功值或异常），带超时防护。
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
    void concurrentMerges_exactlyOnePublishes() throws Exception {
        commanding("INC-CM", "alice");
        createPlan("INC-CM", List.of(task("A", "ta", "u1")));
        // 两对草稿（v2/v3、v4/v5），两个合并请求并发
        plans.createDraft("INC-CM", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-CM", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        plans.createDraft("INC-CM", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-CM", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));

        List<Object> results = runConcurrently(List.of(
                () -> plans.merge("INC-CM", "alice", mergeReq("REQ-A", "MK-A", 1, 2, 3, 0, 0)),
                () -> plans.merge("INC-CM", "alice", mergeReq("REQ-B", "MK-B", 1, 4, 5, 0, 0))));

        long successes = results.stream().filter(MergeResultView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        // 只发布一个新版本，活动版本唯一，无混合版本
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions v JOIN incidents i ON i.id = v.incident_id"
                        + " WHERE i.incident_key = 'INC-CM' AND v.status = 'PUBLISHED'",
                Integer.class);
        assertThat(published).isEqualTo(1);
        Integer mergeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_merges", Integer.class);
        assertThat(mergeRows).isEqualTo(1);
        PlanVersionView active = plans.getActivePlan("INC-CM");
        assertThat(active.versionNo()).isEqualTo(6);
    }

    @Test
    void concurrentMergeAndTaskExecution_commitOrderConsistent() throws Exception {
        commanding("INC-CT", "alice");
        createPlan("INC-CT", List.of(task("A", "ta", "u1"), task("B", "tb", "u2")));
        plans.createDraft("INC-CT", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-CT", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        // 左支新增任务 C（不触碰 A/B），与并发启动 A 互不冲突
        plans.upsertDraftTask("INC-CT", 2, "alice",
                new DraftTaskRequest(key(), "C", "G", "tc", "u3"));

        List<Object> results = runConcurrently(List.of(
                () -> plans.merge("INC-CT", "alice", mergeReq("REQ-M", "MK-M", 1, 2, 3, 1, 0)),
                () -> plans.startTask("INC-CT", "A", "alice", new PlanTaskActionRequest(key()))));

        // 两种提交顺序都合法：执行先提交则合并看到 IN_PROGRESS 的 A（保留），
        // 合并先发布则启动作用于新版本；两者都应成功
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        PlanVersionView active = plans.getActivePlan("INC-CT");
        assertThat(active.versionNo()).isEqualTo(4);
        assertThat(active.tasks()).extracting(PlanTaskView::taskId)
                .containsExactly("A", "B", "C");
        assertThat(active.tasks()).filteredOn(t -> t.taskId().equals("A"))
                .singleElement().extracting(PlanTaskView::status).isEqualTo("IN_PROGRESS");
        assertThat(active.tasks()).filteredOn(t -> t.taskId().equals("C"))
                .singleElement().extracting(PlanTaskView::status).isEqualTo("PENDING");
    }

    @Test
    void concurrentMergeAndDraftEdit_exactlyOneWins() throws Exception {
        commanding("INC-CE", "alice");
        createPlan("INC-CE", List.of(task("A", "ta", "u1")));
        plans.createDraft("INC-CE", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-CE", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));

        List<Object> results = runConcurrently(List.of(
                () -> plans.merge("INC-CE", "alice", mergeReq("REQ-E", "MK-E", 1, 2, 3, 0, 0)),
                () -> plans.upsertDraftTask("INC-CE", 2, "alice",
                        new DraftTaskRequest(key(), "A", "G", "并发改", "u1"))));

        long mergeOk = results.stream().filter(MergeResultView.class::isInstance).count();
        long editOk = results.stream().filter(PlanVersionView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        // 编辑先提交则合并 409（revision 不符）；合并先发布则编辑 409（分支已 MERGED）
        assertThat(mergeOk + editOk).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        PlanVersionView draft2 = plans.getVersion("INC-CE", 2);
        if (mergeOk == 1) {
            assertThat(draft2.status()).isEqualTo("MERGED");
            assertThat(plans.getActivePlan("INC-CE").versionNo()).isEqualTo(4);
        } else {
            assertThat(draft2.status()).isEqualTo("DRAFT");
            assertThat(draft2.revision()).isEqualTo(1);
            assertThat(plans.getActivePlan("INC-CE").versionNo()).isEqualTo(1);
        }
    }

    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        commanding("INC-CR", "alice");
        createPlan("INC-CR", List.of(task("A", "ta", "u1")));
        plans.createDraft("INC-CR", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-CR", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));

        List<Callable<MergeResultView>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> plans.merge("INC-CR", "alice",
                    mergeReq("REQ-SAME", "MK-SAME", 1, 2, 3, 0, 0)));
        }
        List<Object> results = runConcurrently(calls);
        List<MergeResultView> successes = results.stream()
                .filter(MergeResultView.class::isInstance)
                .map(MergeResultView.class::cast).toList();
        // 全部成功且响应一致（一个执行、其余重放首次快照）
        assertThat(successes).hasSize(3);
        assertThat(successes).allSatisfy(r -> assertThat(r).isEqualTo(successes.get(0)));
        Integer mergeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_merges WHERE request_id = 'REQ-SAME'", Integer.class);
        assertThat(mergeRows).isEqualTo(1);
        assertThat(plans.getActivePlan("INC-CR").versionNo()).isEqualTo(4);
    }
}
