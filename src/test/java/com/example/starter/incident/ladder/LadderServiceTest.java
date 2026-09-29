package com.example.starter.incident.ladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.ControllableClock;
import com.example.starter.incident.IncidentService;
import com.example.starter.incident.ladder.dto.LadderRequests.BatchScoreItem;
import com.example.starter.incident.ladder.dto.LadderRequests.BatchScoreRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.DemoteRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.PublishVersionRequest;
import com.example.starter.incident.ladder.dto.LadderResponses.BatchScoreResponse;
import com.example.starter.incident.ladder.dto.LadderResponses.DemoteResponse;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderDetailView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderDiagnosticsView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderHistoryItemView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderHistoryView;
import com.example.starter.incident.ladder.dto.LadderResponses.ThresholdVersionView;
import com.example.starter.incident.dto.Requests.ReportRequest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级阶梯服务测试（真实 H2 内存库，MODE=MySQL）：覆盖版本校验与发布、等级裁决、
 * 批量更新整批后态校验与回滚、自动升级、逐级降级、冷却左闭右开微秒边界、
 * 阈值版本快照不可变、commandKey 幂等与只读诊断。时间用可控 Clock，无真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LadderServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-29T00:00:00Z");

    /** 默认版本阈值：1 级 0、2 级 10、3 级 30、4 级 60、5 级 100。 */
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

    private String report(String incidentKey) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "阶梯测试事件", "reporter-1"));
        return incidentKey;
    }

    private ThresholdVersionView publishV1() {
        return ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "V1", T1, 10, "首版阈值，冷却10分钟"));
    }

    private BatchScoreResponse batch(String actor, BatchScoreItem... items) {
        return ladderService.batchUpdateScores(actor,
                new BatchScoreRequest(key(), List.of(items)));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static BatchScoreItem item(String key, long score) {
        return new BatchScoreItem(key, score);
    }

    // ------------------------------------------------------------------
    // 阈值裁决纯函数
    // ------------------------------------------------------------------

    @Test
    void levelFor_thresholdBoundaries() {
        assertThat(LadderService.levelFor(T1, -100)).isEqualTo(1);
        assertThat(LadderService.levelFor(T1, 0)).isEqualTo(1);
        assertThat(LadderService.levelFor(T1, 9)).isEqualTo(1);
        assertThat(LadderService.levelFor(T1, 10)).isEqualTo(2);
        assertThat(LadderService.levelFor(T1, 29)).isEqualTo(2);
        assertThat(LadderService.levelFor(T1, 30)).isEqualTo(3);
        assertThat(LadderService.levelFor(T1, 59)).isEqualTo(4 - 1);
        assertThat(LadderService.levelFor(T1, 60)).isEqualTo(4);
        assertThat(LadderService.levelFor(T1, 99)).isEqualTo(4);
        assertThat(LadderService.levelFor(T1, 100)).isEqualTo(5);
        assertThat(LadderService.levelFor(T1, 1_000_000)).isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // 版本发布
    // ------------------------------------------------------------------

    @Test
    void publish_validVersion_becomesCurrent_andImmutable() {
        ThresholdVersionView view = publishV1();
        assertThat(view.thresholds()).containsExactly(0L, 10L, 30L, 60L, 100L);
        assertThat(view.cooldownMinutes()).isEqualTo(10);
        assertThat(view.createdBy()).isEqualTo("admin");
        assertThat(view.publishedAt()).isEqualTo(T0);
        assertThat(ladderService.listVersions()).hasSize(1);

        // 发布修订版：阈值整体下调、冷却 0；新版本生效，旧版本行仍在
        ((ControllableClock) clock).setInstant(T0.plusSeconds(5));
        ThresholdVersionView v2 = ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "V2", List.of(0L, 5L, 20L, 40L, 80L),
                        0, "下调阈值"));
        assertThat(v2.versionKey()).isEqualTo("V2");
        assertThat(ladderService.listVersions()).hasSize(2);
        LadderDiagnosticsView diag = ladderService.diagnostics();
        assertThat(diag.thresholdVersionKey()).isEqualTo("V2");
    }

    @Test
    void publish_nonStrictThresholds_orIllegalCooldown_returns422() {
        assertApiStatus(() -> ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "BAD1", List.of(1L, 10L, 30L, 60L, 60L), 10, null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "BAD2", List.of(0L, 9L, 9L, 60L, 100L), 10, null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "BAD3", List.of(0L, 10L, 30L, 60L, 59L), 10, null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "BAD4", T1, -1, null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "BAD5", T1, 527041, null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "BAD6", List.of(0L, 10L, 30L, 60L), 10, null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 全部失败，不留版本
        assertThat(ladderService.listVersions()).isEmpty();
    }

    @Test
    void publish_duplicateVersionKey_returns409_andFailureDoesNotOccupyKey() {
        ThresholdVersionView first = ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "VK-DUP", T1, 10, "first"));
        assertThat(first.versionKey()).isEqualTo("VK-DUP");
        // 用新 commandKey 重复版本键 → 409
        String failKey = key();
        assertApiStatus(() -> ladderService.publishVersion("admin",
                new PublishVersionRequest(failKey, "VK-DUP", T1, 10, "second")),
                HttpStatus.CONFLICT);
        // 失败不占 commandKey：同一 commandKey 换合法版本键可成功
        ThresholdVersionView retry = ladderService.publishVersion("admin",
                new PublishVersionRequest(failKey, "VK-OK", T1, 10, "retry"));
        assertThat(retry.versionKey()).isEqualTo("VK-OK");
    }

    // ------------------------------------------------------------------
    // 首次登记 / 自动升级 / 分数下降
    // ------------------------------------------------------------------

    @Test
    void batch_beforeAnyVersion_returns409() {
        report("INC-L-001");
        assertApiStatus(() -> batch("alice", item("INC-L-001", 5)), HttpStatus.CONFLICT);
        assertThat((long) jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_state",
                Long.class)).isZero();
    }

    @Test
    void batch_initialise_setsLevelsAndImmutableHistory() {
        publishV1();
        report("INC-L-010");
        report("INC-L-011");
        report("INC-L-012");
        BatchScoreResponse resp = batch("alice", item("INC-L-010", 5), item("INC-L-011", 20),
                item("INC-L-012", 100));
        assertThat(resp.appliedCount()).isEqualTo(3);
        assertThat(resp.initialised()).isEqualTo(3);
        assertThat(resp.escalated()).isZero();
        assertThat(resp.unchanged()).isZero();
        assertThat(resp.totalScoreDelta()).isEqualTo(125);
        // results 顺序与请求一致
        assertThat(resp.results()).extracting(
                        com.example.starter.incident.ladder.dto.LadderResponses.BatchScoreResultItem::incidentKey)
                .containsExactly("INC-L-010", "INC-L-011", "INC-L-012");
        assertThat(resp.results()).extracting(r -> r.toLevel()).containsExactly(1, 2, 5);
        assertThat(resp.results()).extracting(r -> r.changeType())
                .containsOnly("INIT");

        LadderDetailView d10 = ladderService.detail("INC-L-010");
        assertThat(d10.registered()).isTrue();
        assertThat(d10.score()).isEqualTo(5);
        assertThat(d10.actualLevel()).isEqualTo(1);
        assertThat(d10.requiredLevel()).isEqualTo(1);
        assertThat(d10.levelGap()).isZero();
        assertThat(d10.lastEscalatedBy()).isEqualTo("alice");
        assertThat(d10.thresholdVersionKey()).isEqualTo("V1");

        LadderHistoryView history = ladderService.history("INC-L-012");
        assertThat(history.items()).hasSize(1);
        LadderHistoryItemView init = history.items().get(0);
        assertThat(init.changeType()).isEqualTo("INIT");
        assertThat(init.fromLevel()).isNull();
        assertThat(init.fromScore()).isNull();
        assertThat(init.toLevel()).isEqualTo(5);
        assertThat(init.actor()).isEqualTo("alice");
        assertThat(init.cooldownStartAt()).isEqualTo(T0);
    }

    @Test
    void scoreRise_autoEscalates_canSkipLevels_andRefreshesCooldown() {
        publishV1();
        report("INC-L-020");
        batch("alice", item("INC-L-020", 5));
        ((ControllableClock) clock).setInstant(T0.plus(2, ChronoUnit.MINUTES));
        // 5(L1) → 35(L3)：自动跨级提升
        BatchScoreResponse resp = batch("alice", item("INC-L-020", 35));
        assertThat(resp.escalated()).isEqualTo(1);
        assertThat(resp.results().get(0).fromLevel()).isEqualTo(1);
        assertThat(resp.results().get(0).toLevel()).isEqualTo(3);
        assertThat(resp.totalScoreDelta()).isEqualTo(30);
        LadderDetailView detail = ladderService.detail("INC-L-020");
        assertThat(detail.actualLevel()).isEqualTo(3);
        assertThat(detail.lastEscalatedAt()).isEqualTo(T0.plus(2, ChronoUnit.MINUTES));
        LadderHistoryItemView escalated = ladderService.history("INC-L-020").items().get(1);
        assertThat(escalated.changeType()).isEqualTo("AUTO_ESCALATE");
        assertThat(escalated.fromLevel()).isEqualTo(1);
        assertThat(escalated.toLevel()).isEqualTo(3);
        // 历史行的版本快照为 V1
        assertThat(escalated.thresholdVersionKey()).isEqualTo("V1");
    }

    @Test
    void scoreDrop_neverAutoDemotes_andScoreUpdateKeepsCooldownStart() {
        publishV1();
        report("INC-L-030");
        Instant t5 = T0.plus(5, ChronoUnit.MINUTES);
        ((ControllableClock) clock).setInstant(t5);
        batch("alice", item("INC-L-030", 100));
        Instant t8 = T0.plus(8, ChronoUnit.MINUTES);
        ((ControllableClock) clock).setInstant(t8);
        // 分数降到 0：等级维持 5，冷却起点与升级操作人不变
        BatchScoreResponse resp = batch("alice", item("INC-L-030", 0));
        assertThat(resp.unchanged()).isEqualTo(1);
        assertThat(resp.escalated()).isZero();
        LadderDetailView detail = ladderService.detail("INC-L-030");
        assertThat(detail.actualLevel()).isEqualTo(5);
        assertThat(detail.requiredLevel()).isEqualTo(1);
        assertThat(detail.levelGap()).isEqualTo(-4);
        assertThat(detail.score()).isZero();
        assertThat(detail.lastEscalatedAt()).isEqualTo(t5);
        assertThat(detail.lastEscalatedBy()).isEqualTo("alice");
        LadderHistoryItemView update = ladderService.history("INC-L-030").items().get(1);
        assertThat(update.changeType()).isEqualTo("SCORE_UPDATE");
        assertThat(update.fromLevel()).isEqualTo(5);
        assertThat(update.toLevel()).isEqualTo(5);
        assertThat(update.fromScore()).isEqualTo(100);
        assertThat(update.toScore()).isZero();
        assertThat(update.cooldownStartAt()).isEqualTo(t5);
    }

    @Test
    void batch_validatesFullPostState_anyFailureRollsBackAll() {
        publishV1();
        report("INC-L-040");
        // 一个合法 + 一个不存在：整批回滚，合法事件也不得登记
        assertApiStatus(() -> batch("alice", item("INC-L-040", 5), item("INC-NOPE", 9)),
                HttpStatus.NOT_FOUND);
        assertThat(ladderService.detail("INC-L-040").registered()).isFalse();
        assertThat((long) jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_state",
                Long.class)).isZero();
        assertThat((long) jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_history",
                Long.class)).isZero();

        // 一个合法 + 一个已关闭：整批回滚
        report("INC-L-041");
        report("INC-L-042");
        incidentService.takeover("INC-L-042", "alice",
                new com.example.starter.incident.dto.Requests.TakeoverRequest(key()));
        incidentService.changeStatus("INC-L-042", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-L-042", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus("INC-L-042", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> batch("alice", item("INC-L-041", 5), item("INC-L-042", 5)),
                HttpStatus.CONFLICT);
        assertThat(ladderService.detail("INC-L-041").registered()).isFalse();
    }

    @Test
    void batch_malformed_returns400_andWritesNothing() {
        publishV1();
        report("INC-L-050");
        report("INC-L-051");
        assertApiStatus(() -> ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), List.of(item("INC-L-050", -1)))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), List.of(new BatchScoreItem("INC-L-050", null)))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), List.of(item("INC-L-050", 1),
                        item("INC-L-050", 2)))), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), null)), HttpStatus.BAD_REQUEST);
        assertThat((long) jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_state",
                Long.class)).isZero();
    }

    // ------------------------------------------------------------------
    // 降级
    // ------------------------------------------------------------------

    @Test
    void demote_boundaries_missingScore_closed_level1_sameCommander() {
        publishV1();
        report("INC-L-100");
        // 未登记分数
        assertApiStatus(() -> ladderService.demote("INC-L-100", "bob",
                new DemoteRequest(key(), 2, "理由")), HttpStatus.CONFLICT);

        batch("alice", item("INC-L-100", 5));
        // 1 级不能再降
        assertApiStatus(() -> ladderService.demote("INC-L-100", "bob",
                new DemoteRequest(key(), 1, "理由")), HttpStatus.UNPROCESSABLE_ENTITY);

        // L4 事件：升级操作人 alice，她本人不能降级
        report("INC-L-101");
        batch("alice", item("INC-L-101", 60));
        assertApiStatus(() -> ladderService.demote("INC-L-101", "alice",
                new DemoteRequest(key(), 4, "我自己降")), HttpStatus.UNPROCESSABLE_ENTITY);

        // 已关闭事件不能降级
        report("INC-L-102");
        batch("alice", item("INC-L-102", 60));
        incidentService.takeover("INC-L-102", "alice",
                new com.example.starter.incident.dto.Requests.TakeoverRequest(key()));
        incidentService.changeStatus("INC-L-102", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-L-102", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED"));
        // 冷却已过，但关闭优先拒绝
        ((ControllableClock) clock).setInstant(T0.plus(30, ChronoUnit.MINUTES));
        incidentService.changeStatus("INC-L-102", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> ladderService.demote("INC-L-102", "bob",
                new DemoteRequest(key(), 4, "关闭后降")), HttpStatus.CONFLICT);

        // 锚定等级与当前不一致 → 409（不得跳级）
        assertApiStatus(() -> ladderService.demote("INC-L-101", "bob",
                new DemoteRequest(key(), 3, "试图跳过一级")), HttpStatus.CONFLICT);
    }

    @Test
    void demote_cooldown_halfOpenBoundary_microsecondExact() {
        publishV1();
        report("INC-L-200");
        batch("alice", item("INC-L-200", 60)); // L4，冷却起点 T0，冷却 10 分钟
        Instant boundary = T0.plus(10, ChronoUnit.MINUTES);
        // 恰好等于冷却时长：左闭右开，不允许
        ((ControllableClock) clock).setInstant(boundary);
        assertApiStatus(() -> ladderService.demote("INC-L-200", "bob",
                new DemoteRequest(key(), 4, "边界尝试")), HttpStatus.UNPROCESSABLE_ENTITY);
        // 超 1 微秒：允许
        ((ControllableClock) clock).setInstant(boundary.plus(1, ChronoUnit.MICROS));
        DemoteResponse resp = ladderService.demote("INC-L-200", "bob",
                new DemoteRequest(key(), 4, "风险缓解降一级"));
        assertThat(resp.fromLevel()).isEqualTo(4);
        assertThat(resp.toLevel()).isEqualTo(3);
        assertThat(resp.actor()).isEqualTo("bob");
        assertThat(resp.thresholdVersionKey()).isEqualTo("V1");
        assertThat(resp.demotedAt()).isEqualTo(boundary.plus(1, ChronoUnit.MICROS));
        assertThat(resp.cooldownRequiredMinutes()).isEqualTo(10);
        assertThat(resp.cooldownElapsedSeconds()).isEqualTo(600);
        assertThat(resp.cooldownRequiredSeconds()).isEqualTo(600);
        // 左闭右开：仅超出 1 微秒，按秒计差额为 0，但微秒时刻比较仍允许降级
        assertThat(resp.cooldownOvertimeSeconds()).isZero();
        assertThat(resp.cooldownRequiredMinutes()).isEqualTo(10);

        LadderDetailView detail = ladderService.detail("INC-L-200");
        assertThat(detail.actualLevel()).isEqualTo(3);
        // 降级不刷新冷却起点与操作人
        assertThat(detail.lastEscalatedAt()).isEqualTo(T0);
        assertThat(detail.lastEscalatedBy()).isEqualTo("alice");
        LadderHistoryItemView demote = ladderService.history("INC-L-200").items().get(1);
        assertThat(demote.changeType()).isEqualTo("DEMOTE");
        assertThat(demote.fromLevel()).isEqualTo(4);
        assertThat(demote.toLevel()).isEqualTo(3);
        assertThat(demote.actor()).isEqualTo("bob");
        assertThat(demote.cooldownStartAt()).isEqualTo(T0);
    }

    @Test
    void demote_onlyOneLevelPerStep_andUsesEscalatorAtTimeOfDemote() {
        // 冷却 0：升级后立刻可由他人降级，但每次只降一级
        ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "V0", T1, 0, "无冷却"));
        report("INC-L-210");
        batch("alice", item("INC-L-210", 100)); // L5
        // 冷却 0 也是严格左闭右开：同一微秒不可降，推进 1 微秒即可
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.MICROS));
        ladderService.demote("INC-L-210", "bob", new DemoteRequest(key(), 5, "5→4"));
        assertThat(ladderService.detail("INC-L-210").actualLevel()).isEqualTo(4);
        // 再降必须锚定 4，仍须不同操作人；最近升级操作人始终是 alice，bob 可连续操作
        ladderService.demote("INC-L-210", "bob", new DemoteRequest(key(), 4, "4→3"));
        assertThat(ladderService.detail("INC-L-210").actualLevel()).isEqualTo(3);
        // 旧锚定值已失效
        assertApiStatus(() -> ladderService.demote("INC-L-210", "carol",
                new DemoteRequest(key(), 4, "重放旧锚定")), HttpStatus.CONFLICT);
    }

    @Test
    void demote_cooldownRestartedByLaterEscalation() {
        publishV1();
        report("INC-L-220");
        batch("alice", item("INC-L-220", 60)); // L4 at T0
        ((ControllableClock) clock).setInstant(T0.plus(15, ChronoUnit.MINUTES));
        // 分数再次升高触发升级，冷却起点刷新为 T0+15，升级操作人变为 bob
        batch("bob", item("INC-L-220", 100));
        LadderDetailView before = ladderService.detail("INC-L-220");
        assertThat(before.lastEscalatedAt()).isEqualTo(T0.plus(15, ChronoUnit.MINUTES));
        assertThat(before.lastEscalatedBy()).isEqualTo("bob");
        // 此刻距新冷却起点 0：carol 也不能降级
        assertApiStatus(() -> ladderService.demote("INC-L-220", "carol",
                new DemoteRequest(key(), 5, "冷却被刷新")), HttpStatus.UNPROCESSABLE_ENTITY);
        // bob 是最近升级操作人，永远不能在下次升级前降级
        ((ControllableClock) clock).setInstant(T0.plus(30, ChronoUnit.MINUTES));
        assertApiStatus(() -> ladderService.demote("INC-L-220", "bob",
                new DemoteRequest(key(), 5, "本人尝试")), HttpStatus.UNPROCESSABLE_ENTITY);
        DemoteResponse ok = ladderService.demote("INC-L-220", "carol",
                new DemoteRequest(key(), 5, "超过冷却"));
        assertThat(ok.toLevel()).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void idempotency_replaySnapshot_changedParamsConflict() {
        publishV1();
        report("INC-L-300");
        String batchKey = key();
        BatchScoreResponse first = ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(batchKey, List.of(item("INC-L-300", 35))));
        assertThat(first.results().get(0).toScore()).isEqualTo(35);
        // 同键同参重放：首次完整快照（随后分数已变化也不受影响）
        ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(key(), List.of(item("INC-L-300", 0))));
        BatchScoreResponse replay = ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(batchKey, List.of(item("INC-L-300", 35))));
        assertThat(replay).isEqualTo(first);
        // 同键异参（不同分数）→ 409
        assertApiStatus(() -> ladderService.batchUpdateScores("alice",
                new BatchScoreRequest(batchKey, List.of(item("INC-L-300", 36)))),
                HttpStatus.CONFLICT);
        // 同键不同操作者 → 409（指纹含操作者）
        assertApiStatus(() -> ladderService.batchUpdateScores("bob",
                new BatchScoreRequest(batchKey, List.of(item("INC-L-300", 35)))),
                HttpStatus.CONFLICT);
        // 同键不同操作类型 → 409
        assertApiStatus(() -> ladderService.demote("INC-L-300", "bob",
                new DemoteRequest(batchKey, 3, "占用")), HttpStatus.CONFLICT);
        // 重放不产生历史
        assertThat(ladderService.history("INC-L-300").items()).hasSize(2);
    }

    @Test
    void idempotency_demoteReplay_andFailureReleasesKey() {
        publishV1();
        report("INC-L-310");
        batch("alice", item("INC-L-310", 60));
        String demoteKey = key();
        // 冷却内失败：不占键
        assertApiStatus(() -> ladderService.demote("INC-L-310", "bob",
                new DemoteRequest(demoteKey, 4, "同理由")), HttpStatus.UNPROCESSABLE_ENTITY);
        ((ControllableClock) clock).setInstant(T0.plus(10, ChronoUnit.MINUTES)
                .plus(1, ChronoUnit.MICROS));
        DemoteResponse first = ladderService.demote("INC-L-310", "bob",
                new DemoteRequest(demoteKey, 4, "同理由"));
        assertThat(first.toLevel()).isEqualTo(3);
        // 同键同参重放首次快照，不新增 DEMOTE 行
        DemoteResponse replay = ladderService.demote("INC-L-310", "bob",
                new DemoteRequest(demoteKey, 4, "同理由"));
        assertThat(replay).isEqualTo(first);
        List<LadderHistoryItemView> items = ladderService.history("INC-L-310").items();
        assertThat(items).filteredOn(i -> i.changeType().equals("DEMOTE")).hasSize(1);
        // 同键改 note → 409
        assertApiStatus(() -> ladderService.demote("INC-L-310", "bob",
                new DemoteRequest(demoteKey, 4, "不同理由")), HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------
    // 版本快照不可变 / 诊断 / 只读
    // ------------------------------------------------------------------

    @Test
    void laterVersionRevision_doesNotRewriteHistory_orStateBeforeNextScore() {
        publishV1();
        report("INC-L-400");
        ((ControllableClock) clock).setInstant(T0.plus(1, ChronoUnit.MINUTES));
        batch("alice", item("INC-L-400", 35)); // V1 下 L3
        // 发布大幅下调的新版本
        ((ControllableClock) clock).setInstant(T0.plus(2, ChronoUnit.MINUTES));
        ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "V2", List.of(0L, 5L, 20L, 40L, 80L),
                        0, "下调"));
        // 历史版本键快照保持 V1，当前态裁决版本仍为 V1，直到下次分数更新
        LadderHistoryItemView history = ladderService.history("INC-L-400").items().get(0);
        assertThat(history.thresholdVersionKey()).isEqualTo("V1");
        LadderDetailView stale = ladderService.detail("INC-L-400");
        // 明细的裁决版本显示当前态实际依据 V1，而要求等级按当前生效 V2 计算（35→V2 的 L3）
        assertThat(stale.thresholdVersionKey()).isEqualTo("V1");
        assertThat(stale.requiredLevel()).isEqualTo(3);
        // 用 V2 下等级更高的分数更新后，state 才切到 V2
        ((ControllableClock) clock).setInstant(T0.plus(3, ChronoUnit.MINUTES));
        batch("alice", item("INC-L-400", 39));
        assertThat(ladderService.detail("INC-L-400").thresholdVersionKey()).isEqualTo("V2");
        // 旧证据行仍是 V1
        assertThat(ladderService.history("INC-L-400").items().get(0).thresholdVersionKey())
                .isEqualTo("V1");
    }

    @Test
    void diagnostics_listsRegistered_sortedAndCounted_andReadsDoNotWrite() {
        publishV1();
        report("INC-L-500");
        report("INC-L-501");
        report("INC-L-502");
        batch("alice", item("INC-L-500", 100), item("INC-L-501", 35), item("INC-L-502", 5));
        // 发布阈值下调版本：502 分数 5 在 V2 下要求 L2，实际 L1
        ladderService.publishVersion("admin",
                new PublishVersionRequest(key(), "V2", List.of(0L, 5L, 20L, 40L, 80L),
                        0, "下调"));
        long stateCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_state",
                Long.class);
        long historyCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_ladder_history", Long.class);
        LadderDiagnosticsView diag = ladderService.diagnostics();
        assertThat(diag.registeredCount()).isEqualTo(3);
        assertThat(diag.aboveRequiredCount()).isEqualTo(1);
        assertThat(diag.aboveRequiredRatio()).isEqualTo(1.0d / 3.0d, org.assertj.core.data.Offset.offset(1e-9));
        // 要求等级降序、事件键升序：500→L5、501→L3、502→L2
        assertThat(diag.items()).extracting(LadderDetailView::incidentKey)
                .containsExactly("INC-L-500", "INC-L-501", "INC-L-502");
        // 未登记事件不在诊断内，但明细可查 registered=false
        report("INC-L-503");
        LadderDetailView missing = ladderService.detail("INC-L-503");
        assertThat(missing.registered()).isFalse();
        assertThat(missing.demotable()).isFalse();
        assertThat(missing.blockReason()).isEqualTo("风险分数缺失");
        // 只读查询不改状态
        ladderService.detail("INC-L-500");
        ladderService.history("INC-L-500");
        ladderService.diagnostics();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_state", Long.class))
                .isEqualTo(stateCountBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_ladder_history", Long.class))
                .isEqualTo(historyCountBefore);
        // 事件不存在
        assertApiStatus(() -> ladderService.detail("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> ladderService.history("INC-404"), HttpStatus.NOT_FOUND);
    }
}
