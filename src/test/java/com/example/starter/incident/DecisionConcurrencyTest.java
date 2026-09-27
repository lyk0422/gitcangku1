package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.DecisionCreateRequest;
import com.example.starter.incident.dto.Requests.DecisionExpireCheckRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskReviseRequest;
import com.example.starter.incident.dto.Requests.VoteRequest;
import com.example.starter.incident.dto.Responses.DecisionView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.VoteView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 决策提案并发边界测试：验证同一角色并发投票仅一票、法定人数生效仅一次、
 * 投票与任务修订按提交顺序裁决、同 commandKey 并发单次生效、
 * 到期后并发投票与到期判定形成一致的 EXPIRED 终态。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class DecisionConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_decision_votes");
        jdbc.update("DELETE FROM incident_decisions");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void register(String incidentKey, String actor, String proposalKey, String taskKey,
                          List<String> roles, int quorum) {
        decisionService.register(incidentKey, actor,
                new DecisionCreateRequest(key(), proposalKey, taskKey, "COMPLETE", roles, quorum,
                        T0.plusSeconds(3600)));
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

    private static long countConflicts(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
    }

    @Test
    void concurrentVotes_sameRole_singleVote() throws Exception {
        commanding("INC-C1", "alice");
        incidentService.createTask("INC-C1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        register("INC-C1", "alice", "P-1", "T-1", List.of("ops", "dba", "net"), 3);

        // 同一角色并发投票：恰好一票生效，其余 409
        List<Callable<VoteView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> decisionService.vote("INC-C1", "P-1", "carol",
                    new VoteRequest(key(), "ops", "SUPPORT")));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(VoteView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(2);
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes", Integer.class);
        assertThat(voteCount).isEqualTo(1);
        assertThat(decisionService.get("INC-C1", "P-1").supportCount()).isEqualTo(1);
    }

    @Test
    void concurrentVotes_quorumReachedOnce_taskChangeAtomic() throws Exception {
        commanding("INC-C2", "alice");
        incidentService.createTask("INC-C2", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        register("INC-C2", "alice", "P-1", "T-1", List.of("ops", "dba"), 2);

        // 两个不同角色并发投票：均成功，达法定人数生效恰好一次，任务变更恰好一次
        List<Object> results = runConcurrently(List.of(
                () -> decisionService.vote("INC-C2", "P-1", "carol",
                        new VoteRequest(key(), "ops", "SUPPORT")),
                () -> decisionService.vote("INC-C2", "P-1", "dave",
                        new VoteRequest(key(), "dba", "SUPPORT"))));

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(VoteView.class));
        DecisionView effective = decisionService.get("INC-C2", "P-1");
        assertThat(effective.status()).isEqualTo("EFFECTIVE");
        assertThat(effective.decidedTaskVersion()).isEqualTo(1);
        TaskView task = incidentService.getTask("INC-C2", "T-1");
        assertThat(task.status()).isEqualTo("DONE");
        assertThat(task.doneBy()).isEqualTo("decision:P-1");
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes", Integer.class);
        assertThat(voteCount).isEqualTo(2);
    }

    @Test
    void concurrentVoteAndRevise_commitOrderConsistent() throws Exception {
        commanding("INC-C3", "alice");
        incidentService.createTask("INC-C3", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        register("INC-C3", "alice", "P-1", "T-1", List.of("ops", "dba", "net"), 3);

        // 并发：ops 投票 与 任务修订；两者都成功，最终状态按提交顺序一致
        List<Object> results = runConcurrently(List.of(
                () -> decisionService.vote("INC-C3", "P-1", "carol",
                        new VoteRequest(key(), "ops", "SUPPORT")),
                () -> incidentService.reviseTask("INC-C3", "T-1", "alice",
                        new TaskReviseRequest(key(), "G", "新标题"))));

        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        // 修订必然生效：任务版本 2，提案计票版本 2
        assertThat(incidentService.getTask("INC-C3", "T-1").version()).isEqualTo(2);
        DecisionView proposal = decisionService.get("INC-C3", "P-1");
        assertThat(proposal.currentTaskVersion()).isEqualTo(2);
        // 投票结果与提交顺序一致：先投后修 → 票转 STALE(v1)；先修后投 → 票 CAST(v2)
        var votes = decisionService.history("INC-C3", "P-1").votes();
        assertThat(votes).hasSize(1);
        VoteView vote = votes.get(0);
        if (vote.taskVersion() == 1) {
            assertThat(vote.status()).isEqualTo("STALE");
            assertThat(proposal.supportCount()).isZero();
        } else {
            assertThat(vote.taskVersion()).isEqualTo(2);
            assertThat(vote.status()).isEqualTo("CAST");
            assertThat(proposal.supportCount()).isEqualTo(1);
        }
    }

    @Test
    void concurrentVotes_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-C4", "alice");
        incidentService.createTask("INC-C4", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        register("INC-C4", "alice", "P-1", "T-1", List.of("ops", "dba"), 2);
        String commandKey = key();

        List<Callable<VoteView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> decisionService.vote("INC-C4", "P-1", "carol",
                    new VoteRequest(commandKey, "ops", "SUPPORT")));
        }
        List<Object> results = runConcurrently(tasks);

        List<VoteView> successes = results.stream().filter(VoteView.class::isInstance)
                .map(VoteView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes", Integer.class);
        assertThat(voteCount).isEqualTo(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentVoteAndExpireCheck_consistentExpiredState() throws Exception {
        commanding("INC-C5", "alice");
        incidentService.createTask("INC-C5", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        register("INC-C5", "alice", "P-1", "T-1", List.of("ops", "dba"), 2);
        // 时钟越过到期时刻后并发：投票（410）与到期判定（EXPIRED）
        ((ControllableClock) clock).setInstant(T0.plusSeconds(7200));

        List<Object> results = runConcurrently(List.of(
                () -> decisionService.vote("INC-C5", "P-1", "carol",
                        new VoteRequest(key(), "ops", "SUPPORT")),
                () -> decisionService.expireCheck("INC-C5", "P-1",
                        new DecisionExpireCheckRequest(key()))));

        // 到期判定成功；投票被 410 拒绝；最终一致的 EXPIRED 终态，无半成品
        assertThat(results.get(1)).isInstanceOf(DecisionView.class);
        assertThat(((DecisionView) results.get(1)).status()).isEqualTo("EXPIRED");
        assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.GONE));
        DecisionView end = decisionService.get("INC-C5", "P-1");
        assertThat(end.status()).isEqualTo("EXPIRED");
        assertThat(end.decidedAt()).isEqualTo(T0.plusSeconds(7200));
        Integer voteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_decision_votes", Integer.class);
        assertThat(voteCount).isZero();
        assertThat(incidentService.getTask("INC-C5", "T-1").status()).isEqualTo("OPEN");
    }
}
