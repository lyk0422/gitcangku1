package com.example.starter.incident.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.example.starter.incident.ControllableClock;
import com.example.starter.incident.IncidentService;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.BatchScoreItem;
import com.example.starter.incident.ladder.dto.LadderRequests.BatchScoreRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.DemoteRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.PublishVersionRequest;
import com.example.starter.incident.ladder.dto.LadderResponses.DemoteResponse;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级阶梯并发测试（真实 H2 内存库）：验证同一事件并发降级最多一次生效、
 * 批量分数更新与降级按事务提交顺序裁决且结果自洽、两个交叉批次不产生部分结果。
 * 用可控时钟把事件置于冷却已过状态，协调真实线程并发并设超时，不依赖睡眠判定。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LadderConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-29T00:00:00Z");

    private static final List<Long> T1 = List.of(0L, 10L, 30L, 60L, 100L);

    @Autowired
    private LadderService ladderService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_ladder_history");
        jdbc.update("DELETE FROM incident_ladder_state");
        jdbc.update("DELETE FROM ladder_current_version");
        jdbc.update("DELETE FROM escalation_threshold_versions");
        jdbc.update("DELETE FROM incident_escalations");
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

    private void setupVersionAndIncident(String incidentKey, long score) {
        ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "V-" + incidentKey, T1, 10, "首版"));
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发事件", "r"));
        ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), List.of(new BatchScoreItem(incidentKey, score))));
    }

    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, tasks.size()));
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
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
        for (int round = 0; round < 6; round++) {
            ((ControllableClock) clock).setInstant(T0);
            String ik = "INC-CC-D-" + round;
            setupVersionAndIncident(ik, 60); // L4，冷却起点 T0，冷却 10 分钟
            ((ControllableClock) clock).setInstant(T0.plus(20, ChronoUnit.MINUTES));
            List<Object> results = runConcurrently(List.of(
                    () -> ladderService.demote(ik, "bob", new DemoteRequest(key(), 4, "bob降级")),
                    () -> ladderService.demote(ik, "carol", new DemoteRequest(key(), 4, "carol降级")),
                    () -> ladderService.demote(ik, "dave", new DemoteRequest(key(), 4, "dave降级"))));

            long successes = results.stream().filter(DemoteResponse.class::isInstance).count();
            long conflicts = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(2);

            LadderDetailView end = ladderService.detail(ik);
            assertThat(end.actualLevel()).isEqualTo(3);
            // 只有一条 DEMOTE 历史，冷却起点不被降级刷新
            long demoteRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incident_ladder_history h JOIN incidents i"
                            + " ON i.id = h.incident_id WHERE i.incident_key = ?"
                            + " AND h.change_type = 'DEMOTE'", Long.class, ik);
            assertThat(demoteRows).isEqualTo(1);
            assertThat(end.lastEscalatedAt()).isEqualTo(T0);
            assertThat(end.lastEscalatedBy()).isEqualTo("alice");
        }
    }

    @Test
    void concurrentScoreUpdateAndDemote_commitOrderArbitrated_stateAlwaysConsistent() throws Exception {
        for (int round = 0; round < 10; round++) {
            ((ControllableClock) clock).setInstant(T0);
            String ik = "INC-CC-X-" + round;
            setupVersionAndIncident(ik, 35); // L3，冷却起点 T0
            ((ControllableClock) clock).setInstant(T0.plus(20, ChronoUnit.MINUTES));
            List<Object> results = runConcurrently(List.of(
                    () -> {
                        ladderService.batchUpdateScores("erin", new BatchScoreRequest(key(),
                                List.of(new BatchScoreItem(ik, 100L))));
                        return "SCORED";
                    },
                    () -> ladderService.demote(ik, "bob", new DemoteRequest(key(), 3, "降一级"))));

            long conflicts = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
            long demotes = results.stream().filter(DemoteResponse.class::isInstance).count();

            // 无论谁先提交：最终等级恒为 5、分数为 100，且至多一条 DEMOTE
            LadderDetailView end = ladderService.detail(ik);
            assertThat(end.actualLevel()).isEqualTo(5);
            assertThat(end.score()).isEqualTo(100);

            long demoteRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incident_ladder_history h JOIN incidents i"
                            + " ON i.id = h.incident_id WHERE i.incident_key = ?"
                            + " AND h.change_type = 'DEMOTE'", Long.class, ik);
            assertThat(demoteRows).isEqualTo(demotes);
            if (demotes == 1) {
                // 降级先提交（L3→L2），随后的分数更新自动升回 L5
                assertThat(conflicts).isZero();
                assertThat(demoteRows).isEqualTo(1);
            } else {
                // 分数更新先提交：等级已为 5，锚定 3 的降级冲突，无 DEMOTE
                assertThat(conflicts).isEqualTo(1);
                assertThat(demoteRows).isZero();
            }
        }
    }

    @Test
    void scoreCommittedFirst_staleAnchoredDemote_conflictsNoPartialResult() {
        ((ControllableClock) clock).setInstant(T0);
        String ik = "INC-CC-SF";
        setupVersionAndIncident(ik, 35); // L3
        ((ControllableClock) clock).setInstant(T0.plus(20, ChronoUnit.MINUTES));
        // 分数更新先提交：L3 自动升到 L5，并刷新冷却起点
        ladderService.batchUpdateScores("erin", new BatchScoreRequest(key(),
                List.of(new BatchScoreItem(ik, 100L))));
        // 迟到的降级仍锚定旧等级 3 → 409，不得跳级、不留历史
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ladderService.demote(ik, "bob",
                        new DemoteRequest(key(), 3, "迟到降级")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.status().value()).isEqualTo(409));
        assertThat(ladderService.detail(ik).actualLevel()).isEqualTo(5);
        long demoteRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_ladder_history h JOIN incidents i"
                        + " ON i.id = h.incident_id WHERE i.incident_key = ?"
                        + " AND h.change_type = 'DEMOTE'", Long.class, ik);
        assertThat(demoteRows).isZero();
    }

    @Test
    void demoteCommittedFirst_laterScoreAutoEscalatesBack() {
        ((ControllableClock) clock).setInstant(T0);
        String ik = "INC-CC-DF";
        setupVersionAndIncident(ik, 35); // L3
        ((ControllableClock) clock).setInstant(T0.plus(20, ChronoUnit.MINUTES));
        // 降级先提交：L3→L2
        DemoteResponse demoted = ladderService.demote(ik, "bob",
                new DemoteRequest(key(), 3, "先降级"));
        assertThat(demoted.toLevel()).isEqualTo(2);
        // 随后高分分数更新：按 max(当前等级, 分数等级) 自动升回 L5，历史含先降后升
        ladderService.batchUpdateScores("erin", new BatchScoreRequest(key(),
                List.of(new BatchScoreItem(ik, 100L))));
        LadderDetailView end = ladderService.detail(ik);
        assertThat(end.actualLevel()).isEqualTo(5);
        assertThat(end.score()).isEqualTo(100);
        List<String> types = ladderService.history(ik).items().stream()
                .map(com.example.starter.incident.ladder.dto.LadderResponses.LadderHistoryItemView::changeType)
                .toList();
        assertThat(types).containsExactly("INIT", "DEMOTE", "AUTO_ESCALATE");
    }

    @Test
    void concurrentOverlappingBatches_serializeWithoutPartialResult() throws Exception {
        String ikA = "INC-CC-A";
        String ikB = "INC-CC-B";
        setupVersionAndIncident(ikA, 5);
        incidentService.report(new ReportRequest(ikB, "S2", "并发事件B", "r"));
        ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), List.of(new BatchScoreItem(ikB, 5L))));
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.MINUTES));

        // 两个交叉批次：都含 ikA。事件行按 id 升序加锁，必然串行，两批都应成功
        List<Object> results = runConcurrently(List.of(
                () -> ladderService.batchUpdateScores("alice", new BatchScoreRequest(key(),
                        List.of(new BatchScoreItem(ikA, 30L), new BatchScoreItem(ikB, 30L)))),
                () -> ladderService.batchUpdateScores("bob", new BatchScoreRequest(key(),
                        List.of(new BatchScoreItem(ikA, 60L))))));

        assertThat(results).doesNotHaveAnyElementsOfTypes(Exception.class);
        // 两批均落库，最终态由提交顺序决定但必须自洽：两事件都在 L3 或更高
        assertThat(ladderService.detail(ikA).actualLevel()).isGreaterThanOrEqualTo(3);
        assertThat(ladderService.detail(ikB).actualLevel()).isEqualTo(3);
        long stateRows = jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_state",
                Long.class);
        assertThat(stateRows).isEqualTo(2);
    }
}
