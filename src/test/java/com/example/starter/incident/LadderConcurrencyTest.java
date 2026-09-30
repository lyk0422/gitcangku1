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

import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderVersionCreateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ScoreBatchRequest;
import com.example.starter.incident.dto.Requests.ScoreUpdateItem;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.LadderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级阶梯并发测试（真实 H2 内存库，MODE=MySQL）：
 * 同一事件并发降级最多一次生效；分数更新与关闭按事务提交顺序裁决；
 * 并发批量分数更新在事件行锁下串行化，最终状态自洽。使用可控 Clock 协调真实操作并设超时。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LadderConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");
    private static final List<Integer> THRESHOLDS = List.of(10, 20, 30, 40, 50);

    @Autowired
    private LadderService ladder;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_ladder_history");
        jdbc.update("DELETE FROM incident_ladder_scores");
        jdbc.update("DELETE FROM incident_ladder_states");
        jdbc.update("DELETE FROM ladder_versions");
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

    private void versionZeroCooldown() {
        ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "V0", THRESHOLDS, 0));
    }

    private String atLevel(String incidentKey, int level) {
        incidents.report(new ReportRequest(incidentKey, "S2", "并发阶梯", "r"));
        incidents.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        ladder.submitScores("alice", new ScoreBatchRequest(key(), "V0",
                List.of(new ScoreUpdateItem(incidentKey, THRESHOLDS.get(level - 1)))));
        assertThat(ladder.getLadder(incidentKey).currentLevel()).isEqualTo(level);
        return incidentKey;
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
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
    void concurrentDemotes_sameIncident_onlyOneTakesEffect() throws Exception {
        versionZeroCooldown();
        for (int round = 0; round < 5; round++) {
            String ik = atLevel("INC-CD-" + round, 4);
            List<Object> results = runConcurrently(List.of(
                    () -> ladder.demote(ik, "bob", new LadderDemoteRequest(key())),
                    () -> ladder.demote(ik, "carol", new LadderDemoteRequest(key()))));

            long successes = results.stream().filter(LadderView.class::isInstance).count();
            long conflicts = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);

            // 业务结果：恰好降一级（4→3），恰好一条 DEMOTE 历史
            LadderView end = ladder.getLadder(ik);
            assertThat(end.currentLevel()).isEqualTo(3);
            assertThat(end.history()).filteredOn(h -> "DEMOTE".equals(h.changeType())).hasSize(1);
            long incidentId = jdbc.queryForObject(
                    "SELECT id FROM incidents WHERE incident_key = ?", Long.class, ik);
            Integer demoteRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incident_ladder_history WHERE incident_id = ?"
                            + " AND change_type = 'DEMOTE'", Integer.class, incidentId);
            assertThat(demoteRows).isEqualTo(1);
        }
        // 失败不占键：不存在 response_body 为空的占位残留
        Integer dangling = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE response_body IS NULL", Integer.class);
        assertThat(dangling).isZero();
    }

    @Test
    void concurrentScoreUpdateAndClose_commitOrderDecides_noPartialState() throws Exception {
        versionZeroCooldown();
        for (int round = 0; round < 5; round++) {
            String ik = atLevel("INC-SC-" + round, 2);
            // 推进到 RESOLVED，使“关闭”一步到位，便于与分数更新并发
            incidents.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
            incidents.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
            long incidentId = jdbc.queryForObject(
                    "SELECT id FROM incidents WHERE incident_key = ?", Long.class, ik);

            List<Object> results = runConcurrently(List.of(
                    () -> ladder.submitScores("alice", new ScoreBatchRequest(key(), "V0",
                            List.of(new ScoreUpdateItem(ik, 50)))),
                    (Callable<Object>) () -> incidents.changeStatus(ik, "alice",
                            new StatusRequest(key(), "CLOSED"))));

            long scoreFailures = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
            String finalStatus = incidents.get(ik).status();
            Integer scoreRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incident_ladder_scores WHERE incident_id = ?",
                    Integer.class, incidentId);

            assertThat(finalStatus).isEqualTo("CLOSED");
            if (scoreFailures == 1) {
                // 关闭先提交：分数更新 409 且未留证据/状态
                assertThat(scoreRows).isEqualTo(1); // 仅有初始 atLevel 的一条
                assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(2);
            } else {
                // 分数更新先提交：成功落证据（共 2 条），随后关闭成功
                assertThat(scoreRows).isEqualTo(2);
                assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(5);
            }
            Integer dangling = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM command_keys WHERE response_body IS NULL", Integer.class);
            assertThat(dangling).isZero();
        }
    }

    @Test
    void concurrentScoreBatches_overlappingIncident_serializeConsistently() throws Exception {
        versionZeroCooldown();
        String ik = atLevel("INC-CB-1", 1);
        long incidentId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = ?", Long.class, ik);

        List<Callable<Object>> tasks = new ArrayList<>();
        int[] scores = {35, 55, 25, 45};
        for (int score : scores) {
            tasks.add(() -> ladder.submitScores("alice", new ScoreBatchRequest(key(), "V0",
                    List.of(new ScoreUpdateItem(ik, score)))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        // 四批各提交一条证据，全部落库；最终等级为曾出现的最高等级 5
        Integer scoreRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_ladder_scores WHERE incident_id = ?",
                Integer.class, incidentId);
        assertThat(scoreRows).isEqualTo(1 + scores.length);
        assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(5);
    }
}
