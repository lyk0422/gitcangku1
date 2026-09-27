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

import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Responses.ProposalDetailView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 决策提案并发边界测试（真实 H2）：并发不同角色投票按事务提交顺序裁决、
 * 同一角色并发投票至多一票有效、同 commandKey 并发单次生效、
 * 投票与任务修订并发下计数与版本一致。
 */
@SpringBootTest
class DecisionConcurrencyTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM decision_proposal_votes");
        jdbc.update("DELETE FROM decision_proposal_roles");
        jdbc.update("DELETE FROM decision_proposals");
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

    private void prepare(String incidentKey, String taskKey, String proposalKey,
                         List<String> roles, int quorum) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        incidentService.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "G", "旧标题", List.of()));
        decisionService.createProposal(incidentKey, "alice",
                new ProposalCreateRequest(key(), proposalKey, taskKey, "新标题", roles, quorum,
                        java.time.Instant.parse("2027-01-01T00:00:00Z")));
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

    private static long countConflicts(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
    }

    @Test
    void concurrentDistinctRoleVotes_quorumReachedOnce() throws Exception {
        prepare("INC-C1", "T-1", "P-1", List.of("r1", "r2", "r3", "r4", "r5"), 3);
        List<Callable<ProposalDetailView>> votes = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String role = "r" + i;
            votes.add(() -> decisionService.vote("INC-C1", "P-1", role,
                    new VoteRequest(key(), "SUPPORT")));
        }
        List<Object> results = runConcurrently(votes);

        // 法定人数 3：按提交顺序前 3 票成功并生效，生效后的投票 409
        long successes = results.stream().filter(ProposalDetailView.class::isInstance).count();
        assertThat(successes).isEqualTo(3);
        assertThat(countConflicts(results)).isEqualTo(2);

        ProposalDetailView detail = decisionService.getProposal("INC-C1", "P-1");
        assertThat(detail.proposal().status()).isEqualTo("EFFECTIVE");
        assertThat(detail.proposal().supportCount()).isEqualTo(3);
        assertThat(detail.proposal().remainingToQuorum()).isEqualTo(0);
        assertThat(detail.votes()).hasSize(3);
        // 生效已原子写入任务变更
        TaskView task = incidentService.getTask("INC-C1", "T-1");
        assertThat(task.title()).isEqualTo("新标题");
        assertThat(task.version()).isEqualTo(1);
    }

    @Test
    void concurrentSameRoleVotes_singleActiveVote() throws Exception {
        prepare("INC-C2", "T-1", "P-1", List.of("dba", "ops"), 2);
        List<Callable<ProposalDetailView>> votes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            votes.add(() -> decisionService.vote("INC-C2", "P-1", "dba",
                    new VoteRequest(key(), "SUPPORT")));
        }
        List<Object> results = runConcurrently(votes);

        // 同一角色并发投票：恰好一票生效，其余 409
        long successes = results.stream().filter(ProposalDetailView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(2);
        ProposalDetailView detail = decisionService.getProposal("INC-C2", "P-1");
        assertThat(detail.proposal().supportCount()).isEqualTo(1);
        assertThat(detail.votes()).hasSize(1);
    }

    @Test
    void concurrentSameCommandKey_singleEffect() throws Exception {
        prepare("INC-C3", "T-1", "P-1", List.of("dba", "ops"), 2);
        String commandKey = key();
        List<Callable<ProposalDetailView>> votes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            votes.add(() -> decisionService.vote("INC-C3", "P-1", "dba",
                    new VoteRequest(commandKey, "SUPPORT")));
        }
        List<Object> results = runConcurrently(votes);

        List<ProposalDetailView> successes = results.stream()
                .filter(ProposalDetailView.class::isInstance)
                .map(ProposalDetailView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致（首次快照），且只记录一票
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(decisionService.getProposal("INC-C3", "P-1").votes()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentVoteAndRevise_versionConsistent() throws Exception {
        prepare("INC-C4", "T-1", "P-1", List.of("dba", "ops"), 2);
        List<Object> results = runConcurrently(List.of(
                () -> decisionService.vote("INC-C4", "P-1", "dba",
                        new VoteRequest(key(), "SUPPORT")),
                () -> decisionService.reviseTask("INC-C4", "T-1", "alice",
                        new TaskReviseRequest(key(), "v2 标题"))));

        // 两个操作都成功（无相互冲突），按提交顺序形成一致终态
        assertThat(results.get(0)).isInstanceOf(ProposalDetailView.class);
        assertThat(results.get(1)).isInstanceOf(TaskView.class);
        TaskView task = incidentService.getTask("INC-C4", "T-1");
        assertThat(task.version()).isEqualTo(2);
        ProposalDetailView detail = decisionService.getProposal("INC-C4", "P-1");
        assertThat(detail.votes()).hasSize(1);
        var vote = detail.votes().get(0);
        if ("CAST".equals(vote.status())) {
            // 修订先提交：投票固化新版本 2 并计入
            assertThat(vote.taskVersion()).isEqualTo(2);
            assertThat(detail.proposal().supportCount()).isEqualTo(1);
        } else {
            // 投票先提交：修订将其转为 STALE，不计入新版本
            assertThat(vote.status()).isEqualTo("STALE");
            assertThat(vote.taskVersion()).isEqualTo(1);
            assertThat(detail.proposal().supportCount()).isEqualTo(0);
        }
    }
}
