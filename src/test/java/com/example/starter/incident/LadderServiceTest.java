package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderVersionCreateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ScoreBatchRequest;
import com.example.starter.incident.dto.Requests.ScoreUpdateItem;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.LadderHistoryView;
import com.example.starter.incident.dto.Responses.LadderVersionView;
import com.example.starter.incident.dto.Responses.LadderView;
import com.example.starter.incident.dto.Responses.ScoreBatchView;
import com.example.starter.incident.dto.Responses.ScoreUpdateResultView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级阶梯服务测试：版本化阈值、左闭右开定级、自动升级（含跨级）、人工降级的
 * 确认人/冷却/逐级约束、批量整批校验与回滚、commandKey 幂等、不可变历史与
 * 实际值/要求值/差额语义。时间用可控 Clock，不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LadderServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    /** 阈值版本 V1：[10,20)→1 …… [50,+∞)→5，冷却 30 分钟。 */
    private static final List<Integer> T_V1 = List.of(10, 20, 30, 40, 50);

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

    private LadderVersionView version(String versionKey, List<Integer> thresholds, int cooldown) {
        return ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), versionKey, thresholds, cooldown));
    }

    private LadderVersionView v1() {
        return version("V1", T_V1, 30);
    }

    private String commanding(String incidentKey, String commander) {
        incidents.report(new ReportRequest(incidentKey, "S2", "核心链路风险升高", "reporter-1"));
        incidents.takeover(incidentKey, commander, new TakeoverRequest(key()));
        return incidentKey;
    }

    private ScoreBatchView scores(String versionKey, String actor, ScoreUpdateItem... items) {
        return ladder.submitScores(actor,
                new ScoreBatchRequest(key(), versionKey, List.of(items)));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void createVersion_validatesStrictlyIncreasingThresholds_andCooldownRange() {
        // 正常版本
        LadderVersionView view = v1();
        assertThat(view.thresholds()).containsExactly(10, 20, 30, 40, 50);
        assertThat(view.cooldownMinutes()).isEqualTo(30);
        assertThat(view.createdBy()).isEqualTo("admin");

        // 阈值非严格递增 → 422
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "BAD1", List.of(10, 20, 20, 40, 50), 30)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "BAD2", List.of(50, 40, 30, 20, 10), 30)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "BAD3", List.of(1, 2, 3, 4), 30)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 冷却越界与缺失 → 422
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "BAD4", T_V1, -1)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "BAD5", T_V1, 7 * 24 * 60 + 1)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "BAD6", T_V1, null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 边界冷却 0 与 10080 合法
        assertThat(version("CD0", T_V1, 0).cooldownMinutes()).isZero();
        assertThat(version("CDMAX", T_V1, 7 * 24 * 60).cooldownMinutes()).isEqualTo(7 * 24 * 60);
    }

    @Test
    void duplicateVersionKey_conflicts_andFailedCreateDoesNotOccupyKey() {
        v1();
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(key(), "V1", T_V1, 30)), HttpStatus.CONFLICT);
        // 失败不占键：同一 commandKey 在参数非法失败后可用于成功请求
        String reusable = key();
        assertApiStatus(() -> ladder.createVersion("admin",
                new LadderVersionCreateRequest(reusable, "BAD", List.of(1, 1, 1, 1, 1), 30)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ladder.createVersion("admin",
                new LadderVersionCreateRequest(reusable, "V2", T_V1, 10)).versionKey())
                .isEqualTo("V2");
    }

    @Test
    void scoreLevels_leftClosedRightOpen_andFirstEntryPromotes() {
        v1();
        String ik = commanding("INC-L-1", "alice");
        // 低于一级阈值：不进入阶梯
        ScoreBatchView none = scores("V1", "alice", new ScoreUpdateItem(ik, 9));
        ScoreUpdateResultView r0 = none.results().get(0);
        assertThat(r0.requiredLevel()).isNull();
        assertThat(r0.actualLevel()).isNull();
        assertThat(r0.levelDelta()).isZero();
        assertThat(r0.changed()).isFalse();
        assertThat(ladder.getLadder(ik).onLadder()).isFalse();

        // 左闭：score=10 恰好 1 级
        ScoreUpdateResultView r1 = scores("V1", "alice", new ScoreUpdateItem(ik, 10))
                .results().get(0);
        assertThat(r1.requiredLevel()).isEqualTo(1);
        assertThat(r1.actualLevel()).isEqualTo(1);
        assertThat(r1.previousLevel()).isNull();
        assertThat(r1.changed()).isTrue();

        // 右开：score=19 仍 1 级；score=20 升入 2 级
        assertThat(scores("V1", "alice", new ScoreUpdateItem(ik, 19)).results().get(0)
                .changed()).isFalse();
        ScoreUpdateResultView r2 = scores("V1", "alice", new ScoreUpdateItem(ik, 20))
                .results().get(0);
        assertThat(r2.previousLevel()).isEqualTo(1);
        assertThat(r2.requiredLevel()).isEqualTo(2);
        assertThat(r2.actualLevel()).isEqualTo(2);
        assertThat(r2.levelDelta()).isZero();

        LadderView view = ladder.getLadder(ik);
        assertThat(view.currentLevel()).isEqualTo(2);
        assertThat(view.lastScore()).isEqualTo(20);
        assertThat(view.thresholdVersionKey()).isEqualTo("V1");
        // 历史：入级 PROMOTE(→1)、升级 PROMOTE(1→2)
        assertThat(view.history()).extracting(LadderHistoryView::changeType)
                .containsExactly("PROMOTE", "PROMOTE");
        assertThat(view.history().get(0).fromLevel()).isNull();
        assertThat(view.history().get(0).toLevel()).isEqualTo(1);
    }

    @Test
    void scoreDrop_doesNotAutoDemote_butActualRequiredDeltaReported() {
        v1();
        String ik = commanding("INC-L-2", "alice");
        scores("V1", "alice", new ScoreUpdateItem(ik, 55));
        assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(5);
        // 分数回落到 1 级区间：不自动降级；actual=5、required=1、差额=4
        ScoreUpdateResultView dropped = scores("V1", "alice", new ScoreUpdateItem(ik, 10))
                .results().get(0);
        assertThat(dropped.changed()).isFalse();
        assertThat(dropped.requiredLevel()).isEqualTo(1);
        assertThat(dropped.actualLevel()).isEqualTo(5);
        assertThat(dropped.levelDelta()).isEqualTo(4);
        LadderView view = ladder.getLadder(ik);
        assertThat(view.currentLevel()).isEqualTo(5);
        assertThat(view.lastScore()).isEqualTo(10);
        // 分数证据已追加两条，历史仅一条入级升级
        assertThat(view.scores()).hasSize(2);
        assertThat(view.history()).hasSize(1);
    }

    @Test
    void promotion_canCrossMultipleLevels_andRefreshesCooldownStart() {
        v1();
        String ik = commanding("INC-L-3", "alice");
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.HOURS));
        scores("V1", "alice", new ScoreUpdateItem(ik, 25));
        LadderView at2 = ladder.getLadder(ik);
        assertThat(at2.currentLevel()).isEqualTo(2);
        assertThat(at2.lastPromotedAt()).isEqualTo(T0.plus(1, ChronoUnit.HOURS));

        // 跨级升到 5，冷却起点刷新
        ((ControllableClock) clock).setInstant(T0.plus(2, ChronoUnit.HOURS));
        scores("V1", "alice", new ScoreUpdateItem(ik, 80));
        LadderView at5 = ladder.getLadder(ik);
        assertThat(at5.currentLevel()).isEqualTo(5);
        assertThat(at5.lastPromotedAt()).isEqualTo(T0.plus(2, ChronoUnit.HOURS));
        LadderHistoryView jump = at5.history().get(at5.history().size() - 1);
        assertThat(jump.changeType()).isEqualTo("PROMOTE");
        assertThat(jump.fromLevel()).isEqualTo(2);
        assertThat(jump.toLevel()).isEqualTo(5);
        assertThat(jump.cooldownSince()).isEqualTo(T0.plus(2, ChronoUnit.HOURS));
    }

    @Test
    void demote_requiresDifferentCommander_cooldown_andSingleStep() {
        v1();
        String ik = commanding("INC-L-4", "alice");
        scores("V1", "alice", new ScoreUpdateItem(ik, 50));
        assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(5);

        // 当前指挥人本人不能确认降级 → 409
        assertApiStatus(() -> ladder.demote(ik, "alice", new LadderDemoteRequest(key())),
                HttpStatus.CONFLICT);

        // 冷却未到：T0 升级，T0+20 分钟降级 → 409，差额明细
        ((ControllableClock) clock).setInstant(T0.plus(20, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> ladder.demote(ik, "bob", new LadderDemoteRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> details = (java.util.Map<String, Object>) e.details();
                    assertThat(details.get("requiredCooldownMinutes")).isEqualTo(30);
                    assertThat(details.get("remainingMinutes")).isEqualTo(10L);
                });
        assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(5);

        // 冷却时刻相等即满足（左闭右开）：T0+30 可降级
        ((ControllableClock) clock).setInstant(T0.plus(30, ChronoUnit.MINUTES));
        LadderView demoted = ladder.demote(ik, "bob", new LadderDemoteRequest(key()));
        assertThat(demoted.currentLevel()).isEqualTo(4);
        assertThat(demoted.history()).last().satisfies(h -> {
            assertThat(h.changeType()).isEqualTo("DEMOTE");
            assertThat(h.fromLevel()).isEqualTo(5);
            assertThat(h.toLevel()).isEqualTo(4);
            assertThat(h.operator()).isEqualTo("bob");
            // 降级不刷新冷却起点
            assertThat(h.cooldownSince()).isEqualTo(T0);
        });
        assertThat(demoted.lastPromotedAt()).isEqualTo(T0);
        assertThat(demoted.demoteReady()).isTrue();

        // 逐级：再降一级到 3，不能跨级
        ((ControllableClock) clock).setInstant(T0.plus(61, ChronoUnit.MINUTES));
        assertThat(ladder.demote(ik, "carol", new LadderDemoteRequest(key())).currentLevel())
                .isEqualTo(3);
    }

    @Test
    void demote_atLevel1_orNotOnLadder_conflicts() {
        v1();
        String ik = commanding("INC-L-5", "alice");
        scores("V1", "alice", new ScoreUpdateItem(ik, 10));
        // 冷却设为 0 的版本用于本测：重新用 V1（冷却30）在冷却满足后把 1 级事件降级被拒
        ((ControllableClock) clock).setInstant(T0.plus(60, ChronoUnit.MINUTES));
        assertApiStatus(() -> ladder.demote(ik, "bob", new LadderDemoteRequest(key())),
                HttpStatus.CONFLICT);
        // 从未进入阶梯的事件不能降级
        String ik2 = commanding("INC-L-6", "alice");
        assertApiStatus(() -> ladder.demote(ik2, "bob", new LadderDemoteRequest(key())),
                HttpStatus.CONFLICT);
        // 不存在 → 404
        assertApiStatus(() -> ladder.demote("INC-NOPE", "bob", new LadderDemoteRequest(key())),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void batch_validatesAllFinalStates_anyFailureRollsBackWholeBatch() {
        v1();
        String ok = commanding("INC-B-1", "alice");
        String closed = commanding("INC-B-2", "alice");
        // 将 closed 事件推进到 CLOSED
        incidents.changeStatus(closed, "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        incidents.changeStatus(closed, "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED"));
        incidents.changeStatus(closed, "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CLOSED"));
        String missing = "INC-B-MISSING";

        // 混合失败：一个关闭(409 原因) + 一个不存在(422 原因) → 422，逐条原因
        assertThatThrownBy(() -> ladder.submitScores("alice", new ScoreBatchRequest(key(), "V1",
                List.of(new ScoreUpdateItem(closed, 50), new ScoreUpdateItem(missing, 50),
                        new ScoreUpdateItem(ok, 50)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    @SuppressWarnings("unchecked")
                    List<java.util.Map<String, String>> details =
                            (List<java.util.Map<String, String>>) e.details();
                    assertThat(details).extracting(d -> d.get("incidentKey"))
                            .containsExactly(closed, missing);
                    assertThat(details).extracting(d -> d.get("reason"))
                            .containsExactly("INCIDENT_CLOSED", "INCIDENT_NOT_FOUND");
                });

        // 整批回滚：三个事件都无分数证据、无状态，command_keys 无占位（失败不占键）
        for (String ik : List.of(ok, closed)) {
            Long incidentId = jdbc.queryForObject(
                    "SELECT id FROM incidents WHERE incident_key = ?", Long.class, ik);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_scores WHERE incident_id=?",
                    Integer.class, incidentId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_states WHERE incident_id=?",
                    Integer.class, incidentId)).isZero();
        }
        Integer placeholders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE response_body IS NULL", Integer.class);
        assertThat(placeholders).isZero();
        // 失败批次的 commandKey 可被成功复用
        String reusable = key();
        assertApiStatus(() -> ladder.submitScores("alice", new ScoreBatchRequest(reusable, "V1",
                List.of(new ScoreUpdateItem(closed, 50)))), HttpStatus.CONFLICT);
        assertThat(ladder.submitScores("alice", new ScoreBatchRequest(reusable, "V1",
                List.of(new ScoreUpdateItem(ok, 50)))).results().get(0).actualLevel())
                .isEqualTo(5);

        // 全部为关闭事件 → 409
        assertApiStatus(() -> ladder.submitScores("alice", new ScoreBatchRequest(key(), "V1",
                List.of(new ScoreUpdateItem(closed, 10)))), HttpStatus.CONFLICT);
    }

    @Test
    void batch_malformed_422_missingScore_empty_duplicate_unknownVersion() {
        v1();
        String ik = commanding("INC-B-3", "alice");
        assertApiStatus(() -> ladder.submitScores("alice",
                new ScoreBatchRequest(key(), "V1", List.of())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladder.submitScores("alice",
                new ScoreBatchRequest(key(), "V1", null)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 分数缺失 → 422
        assertApiStatus(() -> ladder.submitScores("alice", new ScoreBatchRequest(key(), "V1",
                List.of(new ScoreUpdateItem(ik, null)))), HttpStatus.UNPROCESSABLE_ENTITY);
        // 批内同一事件重复 → 422
        assertApiStatus(() -> ladder.submitScores("alice", new ScoreBatchRequest(key(), "V1",
                List.of(new ScoreUpdateItem(ik, 10), new ScoreUpdateItem(ik, 20)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 未知阈值版本 → 422
        assertApiStatus(() -> ladder.submitScores("alice", new ScoreBatchRequest(key(), "NOPE",
                List.of(new ScoreUpdateItem(ik, 10)))), HttpStatus.UNPROCESSABLE_ENTITY);
        // 上述失败均未写入
        Integer scoreRows = jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_scores",
                Integer.class);
        assertThat(scoreRows).isZero();
    }

    @Test
    void batch_results_sortedByKey_andIndependentAcrossIncidents() {
        v1();
        String a = commanding("INC-S-2", "alice");
        String b = commanding("INC-S-1", "alice");
        // 请求顺序与规范化（事件键升序）不同，响应按键升序
        ScoreBatchView view = ladder.submitScores("alice", new ScoreBatchRequest(key(), "V1",
                List.of(new ScoreUpdateItem(a, 50), new ScoreUpdateItem(b, 20))));
        assertThat(view.results()).extracting(ScoreUpdateResultView::incidentKey)
                .containsExactly(b, a);
        assertThat(view.results()).extracting(ScoreUpdateResultView::actualLevel)
                .containsExactly(2, 5);
    }

    @Test
    void idempotency_sameKeyReplaysSnapshot_changedParamsConflict() {
        v1();
        String ik = commanding("INC-IDEM", "alice");
        String batchKey = key();
        ScoreBatchRequest req25 = new ScoreBatchRequest(batchKey, "V1",
                List.of(new ScoreUpdateItem(ik, 25)));
        ScoreBatchView first = ladder.submitScores("alice", req25);
        assertThat(first.results().get(0).actualLevel()).isEqualTo(2);

        // 同键同参重放：返回首次完整响应快照，不重新评估、不追加证据
        ScoreBatchView replay = ladder.submitScores("alice", req25);
        assertThat(replay).isEqualTo(first);
        Long incidentId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = ?", Long.class, ik);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_scores WHERE incident_id=?",
                Integer.class, incidentId)).isEqualTo(1);

        // 同键改参（不同分数）→ 409，且不改变状态
        assertApiStatus(() -> ladder.submitScores("alice", new ScoreBatchRequest(batchKey, "V1",
                List.of(new ScoreUpdateItem(ik, 50)))), HttpStatus.CONFLICT);
        assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(2);

        // 降级同键重放：冷却满足后降到 1，返回首次快照；再次同键调用不重复降级
        version("V0", T_V1, 0);
        ((ControllableClock) clock).setInstant(T0.plus(30, ChronoUnit.MINUTES));
        String dKey = key();
        LadderView firstDemote = ladder.demote(ik, "bob", new LadderDemoteRequest(dKey));
        assertThat(firstDemote.currentLevel()).isEqualTo(1);
        LadderView replayDemote = ladder.demote(ik, "bob", new LadderDemoteRequest(dKey));
        assertThat(replayDemote).isEqualTo(firstDemote);
        // 同键换操作人（异参）→ 409
        assertApiStatus(() -> ladder.demote(ik, "carol", new LadderDemoteRequest(dKey)),
                HttpStatus.CONFLICT);
        assertThat(ladder.getLadder(ik).currentLevel()).isEqualTo(1);
    }

    @Test
    void laterVersionRevision_doesNotRewriteHistoryOrScores() {
        v1();
        String ik = commanding("INC-REV", "alice");
        scores("V1", "alice", new ScoreUpdateItem(ik, 25));
        // 新建更严格版本 V2：25 在 V2 仅对应 1 级
        version("V2", List.of(20, 40, 60, 80, 100), 0);
        scores("V2", "alice", new ScoreUpdateItem(ik, 25));

        LadderView view = ladder.getLadder(ik);
        // 当前等级不因新版本或低分数回退（仍 2），最近使用版本变为 V2
        assertThat(view.currentLevel()).isEqualTo(2);
        assertThat(view.lastScore()).isEqualTo(25);
        assertThat(view.thresholdVersionKey()).isEqualTo("V2");
        // 旧证据仍记录 V1 与当时评估等级 2，未被回写
        assertThat(view.scores()).hasSize(2);
        assertThat(view.scores().get(0).thresholdVersionKey()).isEqualTo("V1");
        assertThat(view.scores().get(0).evaluatedLevel()).isEqualTo(2);
        assertThat(view.scores().get(1).thresholdVersionKey()).isEqualTo("V2");
        assertThat(view.scores().get(1).evaluatedLevel()).isEqualTo(1);
        // 历史仅有一条 V1 入级升级，未被 V2 修订
        assertThat(view.history()).hasSize(1);
        assertThat(view.history().get(0).thresholdVersionKey()).isEqualTo("V1");
    }

    @Test
    void queries_doNotModifyState_andHistoryOrderStableWithNullSemantics() {
        v1();
        String ik = commanding("INC-Q", "alice");
        // 未进入阶梯：多次只读查询
        LadderView empty = ladder.getLadder(ik);
        assertThat(empty.onLadder()).isFalse();
        assertThat(empty.currentLevel()).isNull();
        assertThat(empty.lastScore()).isNull();
        assertThat(empty.thresholdVersionKey()).isNull();
        assertThat(empty.lastPromotedAt()).isNull();
        assertThat(empty.history()).isEmpty();
        assertThat(empty.scores()).isEmpty();
        assertThat(ladder.getLadder(ik)).isEqualTo(empty);
        Integer scoreRows = jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_scores",
                Integer.class);
        assertThat(scoreRows).isZero();

        // 版本列表只读且按创建顺序
        scores("V1", "alice", new ScoreUpdateItem(ik, 35));
        List<LadderVersionView> versions = ladder.listVersions();
        assertThat(versions).extracting(LadderVersionView::versionKey).containsExactly("V1");
    }
}
