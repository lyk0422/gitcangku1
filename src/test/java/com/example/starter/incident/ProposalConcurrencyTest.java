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

import com.example.starter.incident.dto.Requests.EdgeChangeRequest;
import com.example.starter.incident.dto.Requests.ProposalCreateRequest;
import com.example.starter.incident.dto.Requests.ProposalVoteRequest;
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
 * 依赖图变更提案并发边界测试（真实 H2）：并发投票单次激活、
 * 同 requestId 并发投票单次生效、激活与任务图变更按提交顺序收敛、
 * 同 proposalKey 并发创建单行。
 */
@SpringBootTest
class ProposalConcurrencyTest {

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM proposal_votes");
        jdbc.update("DELETE FROM proposal_roster_entries");
        jdbc.update("DELETE FROM dependency_change_proposals");
        jdbc.update("DELETE FROM incident_dependency_edges");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("MERGE INTO dependency_graph_meta (id, graph_version, updated_at)"
                + " KEY (id) VALUES (1, 1, CURRENT_TIMESTAMP(6))");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, tasks.size()));
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
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
    void concurrentVotes_distinctRosterMembers_singleActivation() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        proposalService.create("alice", new ProposalCreateRequest(key(), "PROP-C", 1,
                "说明", "sec", List.of(new EdgeChangeRequest("ADD", "INC-A", "INC-B"))));

        List<Callable<?>> votes = new ArrayList<>();
        for (String person : List.of("alice", "bob", "sec")) {
            votes.add(() -> proposalService.vote("PROP-C", person,
                    new ProposalVoteRequest(key(), "YES")));
        }
        List<Object> results = runConcurrently(votes);

        long activated = results.stream().filter(ProposalView.class::isInstance)
                .map(ProposalView.class::cast).filter(v -> v.status().equals("ACTIVATED"))
                .count();
        assertThat(activated).isEqualTo(1);
        assertThat(results.stream().filter(ApiException.class::isInstance)).isEmpty();
        assertThat(proposalService.get("PROP-C").status()).isEqualTo("ACTIVATED");
        assertThat(jdbc.queryForObject("SELECT graph_version FROM dependency_graph_meta WHERE id = 1",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_dependency_edges",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proposal_votes", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentVotes_samePersonSameRequestId_singleVote() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        proposalService.create("alice", new ProposalCreateRequest(key(), "PROP-I", 1,
                "说明", "sec", List.of(new EdgeChangeRequest("ADD", "INC-A", "INC-B"))));
        String requestId = key();

        List<Callable<?>> votes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            votes.add(() -> proposalService.vote("PROP-I", "alice",
                    new ProposalVoteRequest(requestId, "YES")));
        }
        List<Object> results = runConcurrently(votes);

        List<ProposalView> views = results.stream().filter(ProposalView.class::isInstance)
                .map(ProposalView.class::cast).toList();
        assertThat(views).isNotEmpty();
        assertThat(views).allSatisfy(v -> assertThat(v).isEqualTo(views.get(0)));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM proposal_votes WHERE proposal_id ="
                        + " (SELECT id FROM dependency_change_proposals WHERE proposal_key = 'PROP-I')",
                Integer.class)).isEqualTo(1);
        assertThat(proposalService.get("PROP-I").status()).isEqualTo("PENDING");
    }

    @Test
    void concurrentActivationAndTaskChange_commitOrderConverges() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        // 提案新增 A→B；前两张赞成票先在各自事务提交，仅差 sec 触发票
        proposalService.create("alice", new ProposalCreateRequest(key(), "PROP-G", 1,
                "说明", "sec", List.of(new EdgeChangeRequest("ADD", "INC-A", "INC-B"))));
        proposalService.vote("PROP-G", "alice", new ProposalVoteRequest(key(), "YES"));
        proposalService.vote("PROP-G", "bob", new ProposalVoteRequest(key(), "YES"));

        List<Object> results = runConcurrently(List.of(
                () -> proposalService.vote("PROP-G", "sec",
                        new ProposalVoteRequest(key(), "YES")),
                () -> incidentService.createTask("INC-A", "alice",
                        new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-C")))));

        Object activationResult = results.get(0);
        int version = jdbc.queryForObject(
                "SELECT graph_version FROM dependency_graph_meta WHERE id = 1", Integer.class);
        int edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_dependency_edges", Integer.class);
        // 最终图无环；两种提交顺序均合法
        if (proposalService.get("PROP-G").status().equals("ACTIVATED")) {
            // 激活先提交（版本 2），任务随后提交（版本 3）：两条边
            assertThat(activationResult).isInstanceOf(ProposalView.class);
            assertThat(version).isEqualTo(3);
            assertThat(edgeCount).isEqualTo(2);
        } else {
            // 任务先提交（版本 2）：激活读到版本失配 409，触发票回滚，仅任务边
            assertThat(activationResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(version).isEqualTo(2);
            assertThat(edgeCount).isEqualTo(1);
        }
    }

    @Test
    void concurrentCreate_sameProposalKey_singleInsert() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        List<Callable<?>> creates = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            creates.add(() -> proposalService.create("alice", new ProposalCreateRequest(key(),
                    "PROP-K", 1, "说明", "sec",
                    List.of(new EdgeChangeRequest("ADD", "INC-A", "INC-B")))));
        }
        List<Object> results = runConcurrently(creates);

        long successes = results.stream().filter(ProposalView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dependency_change_proposals WHERE proposal_key = 'PROP-K'",
                Integer.class)).isEqualTo(1);
    }
}
