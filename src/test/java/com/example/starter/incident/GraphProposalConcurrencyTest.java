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

import com.example.starter.incident.dto.Requests.GraphActivateRequest;
import com.example.starter.incident.dto.Requests.GraphProposalCreateRequest;
import com.example.starter.incident.dto.Requests.GraphProposalEdgeRequest;
import com.example.starter.incident.dto.Requests.GraphVoteRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.ProposalView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依赖图提案并发边界测试：验证并发投票按提交顺序收敛且每人一票、
 * 并发激活只生成一个新图版本、激活与其他图变更（任务创建）按全局图锁串行收敛。
 */
@SpringBootTest
class GraphProposalConcurrencyTest {

    @Autowired
    private GraphProposalService graphService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM graph_snapshots");
        jdbc.update("DELETE FROM graph_proposal_votes");
        jdbc.update("DELETE FROM graph_proposal_roster");
        jdbc.update("DELETE FROM graph_proposal_edges");
        jdbc.update("DELETE FROM graph_proposals");
        jdbc.update("DELETE FROM graph_edges");
        jdbc.update("DELETE FROM graph_version");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static GraphProposalEdgeRequest add(String from, String to) {
        return new GraphProposalEdgeRequest("ADD", from, to);
    }

    private ProposalView createApproved(String proposalKey, String reviewer,
                                        GraphProposalEdgeRequest... edges) {
        long version = graphService.getGraph().version();
        ProposalView created = graphService.createProposal("pm",
                new GraphProposalCreateRequest(key(), proposalKey, version, "变更说明", reviewer,
                        List.of(edges)));
        for (var seat : created.roster()) {
            graphService.vote(proposalKey, seat.person(), new GraphVoteRequest(key(), "APPROVE"));
        }
        return graphService.getProposal(proposalKey);
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
    void concurrentVotes_distinctMembers_quorumReachedOnce() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        long version = graphService.getGraph().version();
        graphService.createProposal("pm",
                new GraphProposalCreateRequest(key(), "GP-C1", version, "r", "carol",
                        List.of(add("INC-A", "INC-B"))));

        // 三名名册成员并发投赞成票：全部成功，提案恰好转为 APPROVED
        List<Object> results = runConcurrently(List.of(
                () -> graphService.vote("GP-C1", "alice", new GraphVoteRequest(key(), "APPROVE")),
                () -> graphService.vote("GP-C1", "bob", new GraphVoteRequest(key(), "APPROVE")),
                () -> graphService.vote("GP-C1", "carol", new GraphVoteRequest(key(), "APPROVE"))));

        long successes = results.stream().filter(ProposalView.class::isInstance).count();
        assertThat(successes).isEqualTo(3);
        ProposalView end = graphService.getProposal("GP-C1");
        assertThat(end.status()).isEqualTo("APPROVED");
        assertThat(end.votes()).hasSize(3);
    }

    @Test
    void concurrentVotes_samePerson_singleVote() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        long version = graphService.getGraph().version();
        graphService.createProposal("pm",
                new GraphProposalCreateRequest(key(), "GP-C2", version, "r", "carol",
                        List.of(add("INC-A", "INC-B"))));

        // 同一人并发重复投票（不同 requestId）：恰好一票生效
        List<Object> results = runConcurrently(List.of(
                () -> graphService.vote("GP-C2", "alice", new GraphVoteRequest(key(), "APPROVE")),
                () -> graphService.vote("GP-C2", "alice", new GraphVoteRequest(key(), "APPROVE")),
                () -> graphService.vote("GP-C2", "alice", new GraphVoteRequest(key(), "APPROVE"))));

        long successes = results.stream().filter(ProposalView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(2);
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_proposal_votes", Integer.class);
        assertThat(voteCount).isEqualTo(1);
    }

    @Test
    void concurrentApproveAndReject_rejectedWinsByCommitOrder() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        long version = graphService.getGraph().version();
        graphService.createProposal("pm",
                new GraphProposalCreateRequest(key(), "GP-C3", version, "r", "carol",
                        List.of(add("INC-A", "INC-B"))));

        // 并发一票赞成一票反对：无论提交顺序，最终必然 REJECTED
        runConcurrently(List.of(
                () -> graphService.vote("GP-C3", "alice", new GraphVoteRequest(key(), "APPROVE")),
                () -> graphService.vote("GP-C3", "bob", new GraphVoteRequest(key(), "REJECT"))));

        ProposalView end = graphService.getProposal("GP-C3");
        assertThat(end.status()).isEqualTo("REJECTED");
        // 反对票必定记录；赞成票若先提交也已记录（仅首票约束按人）
        assertThat(end.votes()).isNotEmpty();
        assertThat(end.votes()).anySatisfy(v -> assertThat(v.decision()).isEqualTo("REJECT"));
    }

    @Test
    void concurrentActivations_singleNewVersion() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createApproved("GP-C4", "carol", add("INC-A", "INC-B"));
        long baseVersion = graphService.getGraph().version();

        // 并发激活（不同 requestId）：恰好一个成功，图版本只推进一次
        List<Object> results = runConcurrently(List.of(
                () -> graphService.activate("GP-C4", "alice", new GraphActivateRequest(key())),
                () -> graphService.activate("GP-C4", "bob", new GraphActivateRequest(key())),
                () -> graphService.activate("GP-C4", "carol", new GraphActivateRequest(key()))));

        long successes = results.stream().filter(ProposalView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(2);
        assertThat(graphService.getGraph().version()).isEqualTo(baseVersion + 1);
        Integer edgeCount = jdbc.queryForObject("SELECT COUNT(*) FROM graph_edges", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
        ProposalView end = graphService.getProposal("GP-C4");
        assertThat(end.status()).isEqualTo("ACTIVATED");
        assertThat(end.appliedGraphVersion()).isEqualTo(baseVersion + 1);
    }

    @Test
    void concurrentActivateAndTaskCreate_commitOrderConverges() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        commanding("INC-D", "dave");
        createApproved("GP-C5", "erin", add("INC-A", "INC-B"));
        long baseVersion = graphService.getGraph().version();

        // 并发：激活提案（ADD INC-A->INC-B）与任务创建新增边（INC-C->INC-D）
        List<Object> results = runConcurrently(List.of(
                () -> graphService.activate("GP-C5", "alice", new GraphActivateRequest(key())),
                () -> incidentService.createTask("INC-C", "carol",
                        new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-D")))));

        // 任务创建必然成功；激活是否成功取决于其加锁时版本是否仍匹配
        Object activateResult = results.get(0);
        ProposalView proposal = graphService.getProposal("GP-C5");
        long finalVersion = graphService.getGraph().version();
        if (proposal.status().equals("ACTIVATED")) {
            // 激活先获得图锁：版本匹配，激活成功；任务边随后另行推进版本
            assertThat(activateResult).isInstanceOf(ProposalView.class);
            assertThat(proposal.appliedGraphVersion()).isEqualTo(baseVersion + 1);
            assertThat(finalVersion).isEqualTo(baseVersion + 2);
        } else {
            // 任务创建先提交：版本已推进，激活整案 409，提案保持 APPROVED
            assertThat(proposal.status()).isEqualTo("APPROVED");
            assertThat(activateResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(finalVersion).isEqualTo(baseVersion + 1);
        }
        // 任务边始终存在；提案边仅在激活成功时存在
        var edgeKeys = graphService.getGraph().edges().stream()
                .map(e -> e.fromIncidentKey() + "->" + e.toIncidentKey()).toList();
        assertThat(edgeKeys).contains("INC-C->INC-D");
        assertThat(edgeKeys.contains("INC-A->INC-B"))
                .isEqualTo(proposal.status().equals("ACTIVATED"));
    }

    @Test
    void concurrentCreate_sameRequestId_singleProposal() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        long version = graphService.getGraph().version();
        String requestId = key();
        List<Callable<ProposalView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> graphService.createProposal("pm",
                    new GraphProposalCreateRequest(requestId, "GP-C6", version, "r", "carol",
                            List.of(add("INC-A", "INC-B")))));
        }
        List<Object> results = runConcurrently(tasks);

        List<ProposalView> successes = results.stream().filter(ProposalView.class::isInstance)
                .map(ProposalView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer proposalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_proposals", Integer.class);
        assertThat(proposalCount).isEqualTo(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keyCount).isEqualTo(1);
    }
}
