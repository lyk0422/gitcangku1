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
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ProposalView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 处置提案并发边界测试（真实 H2 内存库，MODE=MySQL）：验证事件行锁 +
 * uk_decision_active_role 唯一索引下同角色一票、达法定人数单次生效、
 * 投票与阻塞解除/任务修订按事务提交顺序裁决、同 commandKey 并发单次生效。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
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
        jdbc.update("DELETE FROM decision_votes");
        jdbc.update("DELETE FROM decision_required_roles");
        jdbc.update("DELETE FROM incident_decisions");
        jdbc.update("DELETE FROM incident_escalations");
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

    private void task(String incidentKey) {
        incidentService.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), "T-1", "DB", "扩容", List.of()));
    }

    private void proposal(String incidentKey, String proposalKey, String type,
                          List<String> roles, int quorum) {
        decisionService.createProposal(incidentKey, "alice",
                new ProposalCreateRequest(key(), proposalKey, "T-1", type,
                        java.time.Instant.parse("2026-09-22T01:40:00Z"), roles, quorum));
    }

    private static List<Object> runConcurrently(List<Callable<ProposalView>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, tasks.size()));
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<ProposalView> task : tasks) {
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
    void concurrentSameRoleVotes_singleCastVote() throws Exception {
        commanding("INC-C1", "alice");
        task("INC-C1");
        proposal("INC-C1", "P-1", "TASK_CANCEL", List.of("OPS", "DB", "SRE"), 3);

        List<Callable<ProposalView>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String actor = "user-" + i;
            tasks.add(() -> decisionService.vote("INC-C1", "P-1", actor,
                    new VoteRequest(key(), "OPS", "SUPPORT")));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(ProposalView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(4);

        // 数据库层兜底：该提案 OPS 角色恰好一张 CAST 票，总支持票 1
        Integer castCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_votes WHERE proposal_id ="
                        + " (SELECT id FROM incident_decisions WHERE proposal_key = 'P-1')"
                        + " AND role_code = 'OPS' AND status = 'CAST'", Integer.class);
        assertThat(castCount).isEqualTo(1);
        ProposalView view = decisionService.getProposal("INC-C1", "P-1");
        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.supportVotes()).isEqualTo(1);
        assertThat(view.remainingVotes()).isEqualTo(2);
    }

    @Test
    void concurrentVotes_reachQuorum_singleEffect() throws Exception {
        commanding("INC-C2", "alice");
        task("INC-C2");
        proposal("INC-C2", "P-1", "TASK_COMPLETE", List.of("OPS", "DB"), 2);

        List<Object> results = runConcurrently(List.of(
                () -> decisionService.vote("INC-C2", "P-1", "u1",
                        new VoteRequest(key(), "OPS", "SUPPORT")),
                () -> decisionService.vote("INC-C2", "P-1", "u2",
                        new VoteRequest(key(), "DB", "SUPPORT"))));

        assertThat(results).allMatch(r -> r instanceof ProposalView);
        List<ProposalView> views = results.stream().map(ProposalView.class::cast).toList();
        // 恰好一个响应看到 OPEN（先提交），另一个看到 EFFECTIVE
        assertThat(views).anySatisfy(v -> assertThat(v.status()).isEqualTo("OPEN"));
        assertThat(views).anySatisfy(v -> assertThat(v.status()).isEqualTo("EFFECTIVE"));

        ProposalView finalView = decisionService.getProposal("INC-C2", "P-1");
        assertThat(finalView.status()).isEqualTo("EFFECTIVE");
        assertThat(finalView.supportVotes()).isEqualTo(2);
        assertThat(finalView.effectiveTaskRevision()).isEqualTo(1);
        assertThat(incidentService.getTask("INC-C2", "T-1").status()).isEqualTo("DONE");
        Integer effectiveCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decisions WHERE status = 'EFFECTIVE'", Integer.class);
        assertThat(effectiveCount).isEqualTo(1);
    }

    @Test
    void concurrentVoteAndBlockerContain_commitOrderWins() throws Exception {
        commanding("INC-C3", "alice");
        commanding("INC-C3B", "bob");
        incidentService.createTask("INC-C3", "alice",
                new TaskCreateRequest(key(), "T-1", "DB", "扩容", List.of("INC-C3B")));
        // 法定人数 1：投票达人数瞬间尝试生效，门禁按提交顺序裁决
        decisionService.createProposal("INC-C3", "alice",
                new ProposalCreateRequest(key(), "P-1", "T-1", "TASK_COMPLETE",
                        java.time.Instant.parse("2026-09-22T01:40:00Z"),
                        List.of("OPS"), 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            return decisionService.vote("INC-C3", "P-1", "u1",
                                    new VoteRequest(key(), "OPS", "SUPPORT"));
                        } catch (Exception e) {
                            return e;
                        }
                    }),
                    pool.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            return incidentService.changeStatus("INC-C3B", "bob",
                                    new StatusRequest(key(), "CONTAINED"));
                        } catch (Exception e) {
                            return e;
                        }
                    }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object voteResult = futures.get(0).get(30, TimeUnit.SECONDS);
            Object containResult = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(containResult).isInstanceOf(IncidentView.class);

            TaskView task = incidentService.getTask("INC-C3", "T-1");
            ProposalView proposal = decisionService.getProposal("INC-C3", "P-1");
            if ("DONE".equals(task.status())) {
                // 阻塞解除先提交：投票事务看到 CONTAINED，生效原子完成
                assertThat(voteResult).isInstanceOf(ProposalView.class);
                assertThat(((ProposalView) voteResult).status()).isEqualTo("EFFECTIVE");
                assertThat(proposal.status()).isEqualTo("EFFECTIVE");
                assertThat(proposal.votes()).hasSize(1);
            } else {
                // 投票先提交：门禁失败整体回滚，任务仍 OPEN，票不留，提案 OPEN 且可重新投
                assertThat(task.status()).isEqualTo("OPEN");
                assertThat(voteResult).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(proposal.status()).isEqualTo("OPEN");
                assertThat(proposal.supportVotes()).isZero();
                assertThat(proposal.votes()).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentVoteAndRevise_commitOrderKeepsVersionsConsistent() throws Exception {
        commanding("INC-C4", "alice");
        task("INC-C4");
        proposal("INC-C4", "P-1", "TASK_COMPLETE", List.of("OPS", "DB"), 2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            return decisionService.vote("INC-C4", "P-1", "u1",
                                    new VoteRequest(key(), "OPS", "SUPPORT"));
                        } catch (Exception e) {
                            return e;
                        }
                    }),
                    pool.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            return decisionService.reviseTask("INC-C4", "T-1", "alice",
                                    new TaskReviseRequest(key(), "修订标题", "修订说明"));
                        } catch (Exception e) {
                            return e;
                        }
                    }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object voteResult = futures.get(0).get(30, TimeUnit.SECONDS);
            Object reviseResult = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(reviseResult).isInstanceOf(TaskView.class);
            assertThat(voteResult).isInstanceOf(ProposalView.class);

            // 无论提交顺序，最终状态必须版本自洽：不存在任务当前版本之外的 CAST 票，提案仍 OPEN
            ProposalView finalView = decisionService.getProposal("INC-C4", "P-1");
            assertThat(finalView.taskRevision()).isEqualTo(2);
            assertThat(finalView.status()).isEqualTo("OPEN");
            Integer strayCast = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM decision_votes v JOIN incident_decisions d"
                            + " ON v.proposal_id = d.id JOIN incident_tasks t"
                            + " ON t.incident_id = d.incident_id AND t.task_key = d.task_key"
                            + " WHERE v.status = 'CAST' AND v.task_revision <> t.revision",
                    Integer.class);
            assertThat(strayCast).isZero();
            Integer staleOrCast = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM decision_votes WHERE status IN ('CAST','STALE')",
                    Integer.class);
            assertThat(staleOrCast).isEqualTo(1);
            // 视图计票口径与版本一致
            if (finalView.supportVotes() == 1) {
                // 修订先提交：票直接落在新版本
                assertThat(finalView.votes().get(0).status()).isEqualTo("CAST");
                assertThat(finalView.votes().get(0).taskRevision()).isEqualTo(2);
            } else {
                // 投票先提交：修订后旧票 STALE
                assertThat(finalView.supportVotes()).isZero();
                assertThat(finalView.remainingVotes()).isEqualTo(2);
                assertThat(finalView.votes().get(0).status()).isEqualTo("STALE");
                assertThat(finalView.votes().get(0).taskRevision()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameCommandKeyVote_singleEffect() throws Exception {
        commanding("INC-C5", "alice");
        task("INC-C5");
        proposal("INC-C5", "P-1", "TASK_CANCEL", List.of("OPS", "DB", "SRE"), 3);
        String commandKey = key();

        List<Callable<ProposalView>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> decisionService.vote("INC-C5", "P-1", "u1",
                    new VoteRequest(commandKey, "OPS", "SUPPORT")));
        }
        List<Object> results = runConcurrently(tasks);

        List<ProposalView> successes = results.stream()
                .filter(ProposalView.class::isInstance)
                .map(ProposalView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        // 只有一张票、一个幂等键记录
        Integer voteRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_votes", Integer.class);
        assertThat(voteRows).isEqualTo(1);
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyRows).isEqualTo(1);
    }
}
