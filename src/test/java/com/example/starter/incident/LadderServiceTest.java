package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.LadderConfigRequest;
import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderScoreBatchRequest;
import com.example.starter.incident.dto.Requests.LadderScoreItem;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.CooldownDetail;
import com.example.starter.incident.dto.Responses.LadderConfigView;
import com.example.starter.incident.dto.Responses.LadderHistoryView;
import com.example.starter.incident.dto.Responses.LadderScoreBatchView;
import com.example.starter.incident.dto.Responses.LadderStateView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级阶梯服务测试（真实 H2）：覆盖配置校验、批量分数更新与自动提升、
 * 降级规则（不同指挥员/冷却/逐级）、整批回滚、历史不可变与幂等语义。
 * 时间推进使用可控 Clock，不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LadderServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private LadderService ladderService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_ladder_history");
        jdbc.update("DELETE FROM incident_ladder_states");
        jdbc.update("DELETE FROM incident_ladder_configs");
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

    private void newIncident(String incidentKey) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "阶梯测试", "r"));
        incidentService.takeover(incidentKey, "alice", new TakeoverRequest(key()));
    }

    private void closeIncident(String incidentKey) {
        incidentService.changeStatus(incidentKey, "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus(incidentKey, "alice", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus(incidentKey, "alice", new StatusRequest(key(), "CLOSED"));
    }

    private LadderConfigView createConfig() {
        return ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40, 70, 100), 30));
    }

    private LadderScoreBatchView updateScores(String actor, LadderScoreItem... items) {
        return ladderService.updateScores(actor,
                new LadderScoreBatchRequest(key(), List.of(items)));
    }

    @Test
    void createConfig_validatesThresholdsAndCooldown() {
        // 阈值不严格递增 → 422
        assertThatThrownBy(() -> ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 10, 40, 70, 100), 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        assertThatThrownBy(() -> ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40, 30, 100), 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        // 阈值个数不对 → 400
        assertThatThrownBy(() -> ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40), 30)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        // 冷却范围非法 → 422
        assertThatThrownBy(() -> ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40, 70, 100), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        assertThatThrownBy(() -> ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40, 70, 100), 0)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        assertThatThrownBy(() -> ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40, 70, 100), 10081)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        // 失败不占键：修正参数后同键可成功
        String reused = key();
        assertThatThrownBy(() -> ladderService.createConfig("ops",
                new LadderConfigRequest(reused, List.of(10, 20, 40, 70, 100), 0)))
                .isInstanceOf(ApiException.class);
        LadderConfigView v1 = ladderService.createConfig("ops",
                new LadderConfigRequest(reused, List.of(10, 20, 40, 70, 100), 30));
        assertThat(v1.configVersion()).isEqualTo(1);
        assertThat(v1.thresholds()).containsExactly(10, 20, 40, 70, 100);
        assertThat(v1.cooldownMinutes()).isEqualTo(30);
        // 版本递增，current 返回最新版本
        LadderConfigView v2 = ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(5, 15, 30, 60, 90), 60));
        assertThat(v2.configVersion()).isEqualTo(2);
        assertThat(ladderService.currentConfig().configVersion()).isEqualTo(2);
    }

    @Test
    void scoreUpdate_autoEscalatesAcrossLevelsAndRecordsHistory() {
        createConfig();
        newIncident("INC-L-1");
        LadderScoreBatchView batch = updateScores("alice", new LadderScoreItem("INC-L-1", 45));
        assertThat(batch.configVersion()).isEqualTo(1);
        assertThat(batch.results()).hasSize(1);
        var result = batch.results().get(0);
        assertThat(result.previousLevel()).isEqualTo(0);
        assertThat(result.currentLevel()).isEqualTo(3);
        assertThat(result.escalated()).isTrue();
        // 实际值/要求值/差额
        assertThat(result.score()).isEqualTo(45);
        assertThat(result.nextLevelThreshold()).isEqualTo(70);
        assertThat(result.gapToNextLevel()).isEqualTo(25);

        LadderStateView state = ladderService.getLadder("INC-L-1");
        assertThat(state.riskScore()).isEqualTo(45);
        assertThat(state.currentLevel()).isEqualTo(3);
        assertThat(state.configVersion()).isEqualTo(1);
        assertThat(state.cooldownStartAt()).isEqualTo(T0);
        assertThat(state.lastOperator()).isEqualTo("alice");

        LadderHistoryView history = ladderService.getHistory("INC-L-1");
        assertThat(history.entries()).hasSize(1);
        var entry = history.entries().get(0);
        assertThat(entry.changeType()).isEqualTo("ESCALATE");
        assertThat(entry.fromLevel()).isEqualTo(0);
        assertThat(entry.toLevel()).isEqualTo(3);
        assertThat(entry.riskScore()).isEqualTo(45);
        assertThat(entry.configVersion()).isEqualTo(1);
        assertThat(entry.actor()).isEqualTo("alice");
        assertThat(entry.occurredAt()).isEqualTo(T0);
    }

    @Test
    void scoreUpdate_belowFirstThreshold_staysLevelZeroWithoutHistory() {
        createConfig();
        newIncident("INC-L-2");
        LadderScoreBatchView batch = updateScores("alice", new LadderScoreItem("INC-L-2", 5));
        var result = batch.results().get(0);
        assertThat(result.currentLevel()).isEqualTo(0);
        assertThat(result.escalated()).isFalse();
        assertThat(result.nextLevelThreshold()).isEqualTo(10);
        assertThat(result.gapToNextLevel()).isEqualTo(5);
        assertThat(ladderService.getHistory("INC-L-2").entries()).isEmpty();
        assertThat(ladderService.getLadder("INC-L-2").riskScore()).isEqualTo(5);
    }

    @Test
    void scoreUpdate_scoreDropDoesNotAutoDemote() {
        createConfig();
        newIncident("INC-L-3");
        updateScores("alice", new LadderScoreItem("INC-L-3", 45));
        LadderScoreBatchView batch = updateScores("alice", new LadderScoreItem("INC-L-3", 12));
        var result = batch.results().get(0);
        assertThat(result.previousLevel()).isEqualTo(3);
        assertThat(result.currentLevel()).isEqualTo(3);
        assertThat(result.escalated()).isFalse();
        LadderStateView state = ladderService.getLadder("INC-L-3");
        assertThat(state.riskScore()).isEqualTo(12);
        assertThat(state.currentLevel()).isEqualTo(3);
        assertThat(ladderService.getHistory("INC-L-3").entries()).hasSize(1);
    }

    @Test
    void scoreUpdate_batchRollsBackWhenAnyIncidentClosed() {
        createConfig();
        newIncident("INC-L-4A");
        newIncident("INC-L-4B");
        closeIncident("INC-L-4B");
        assertThatThrownBy(() -> updateScores("alice",
                new LadderScoreItem("INC-L-4A", 50), new LadderScoreItem("INC-L-4B", 60)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status().value()).isEqualTo(422);
                    assertThat(((ApiException) e).getMessage()).contains("INC-L-4B");
                });
        // 整批回滚：未关闭的事件也没有留下状态或历史
        assertThat(ladderService.getLadder("INC-L-4A").riskScore()).isNull();
        assertThat(ladderService.getHistory("INC-L-4A").entries()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_ladder_states", Integer.class)).isZero();
    }

    @Test
    void scoreUpdate_missingScore_422_andKeyNotOccupied() {
        createConfig();
        newIncident("INC-L-5");
        String commandKey = key();
        assertThatThrownBy(() -> ladderService.updateScores("alice",
                new LadderScoreBatchRequest(commandKey,
                        List.of(new LadderScoreItem("INC-L-5", null)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        // 失败不占键：同键修正参数后成功
        LadderScoreBatchView batch = ladderService.updateScores("alice",
                new LadderScoreBatchRequest(commandKey,
                        List.of(new LadderScoreItem("INC-L-5", 15))));
        assertThat(batch.results().get(0).currentLevel()).isEqualTo(1);
        // 分数越界 → 422
        assertThatThrownBy(() -> updateScores("alice", new LadderScoreItem("INC-L-5", -1)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        // 重复事件键 → 400
        assertThatThrownBy(() -> updateScores("alice",
                new LadderScoreItem("INC-L-5", 15), new LadderScoreItem("INC-L-5", 16)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void scoreUpdate_withoutConfig_409() {
        newIncident("INC-L-6");
        assertThatThrownBy(() -> updateScores("alice", new LadderScoreItem("INC-L-6", 50)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void demote_requiresDifferentOperator() {
        createConfig();
        newIncident("INC-L-7");
        updateScores("alice", new LadderScoreItem("INC-L-7", 45));
        ((ControllableClock) clock).setInstant(T0.plus(31, ChronoUnit.MINUTES));
        // 同一操作人 → 409
        assertThatThrownBy(() -> ladderService.demote("INC-L-7", "alice",
                new LadderDemoteRequest(key(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        // 不同指挥员确认 → 成功降一级
        LadderStateView state = ladderService.demote("INC-L-7", "bob",
                new LadderDemoteRequest(key(), null));
        assertThat(state.currentLevel()).isEqualTo(2);
        assertThat(state.lastOperator()).isEqualTo("bob");
        var entries = ladderService.getHistory("INC-L-7").entries();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).changeType()).isEqualTo("DEESCALATE");
        assertThat(entries.get(1).fromLevel()).isEqualTo(3);
        assertThat(entries.get(1).toLevel()).isEqualTo(2);
        assertThat(entries.get(1).actor()).isEqualTo("bob");
    }

    @Test
    void demote_cooldownWindowLeftClosedRightOpen() {
        createConfig();
        newIncident("INC-L-8");
        updateScores("alice", new LadderScoreItem("INC-L-8", 45));
        // 冷却内（已过 10 分钟，要求 30 分钟）→ 409，附要求/实际/差额
        ((ControllableClock) clock).setInstant(T0.plus(10, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> ladderService.demote("INC-L-8", "bob",
                new LadderDemoteRequest(key(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status().value()).isEqualTo(409);
                    CooldownDetail detail = (CooldownDetail) api.details();
                    assertThat(detail.requiredMinutes()).isEqualTo(30);
                    assertThat(detail.elapsedMinutes()).isEqualTo(10);
                    assertThat(detail.remainingMinutes()).isEqualTo(20);
                });
        // 冷却区间 [T0, T0+30min) 左闭右开：恰好 30 分钟时刻允许降级
        ((ControllableClock) clock).setInstant(T0.plus(30, ChronoUnit.MINUTES));
        LadderStateView state = ladderService.demote("INC-L-8", "bob",
                new LadderDemoteRequest(key(), null));
        assertThat(state.currentLevel()).isEqualTo(2);
        // 降级重置冷却起点：立即再降级 → 409
        assertThatThrownBy(() -> ladderService.demote("INC-L-8", "carol",
                new LadderDemoteRequest(key(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void demote_cannotSkipLevels() {
        createConfig();
        newIncident("INC-L-9");
        updateScores("alice", new LadderScoreItem("INC-L-9", 45));
        ((ControllableClock) clock).setInstant(T0.plus(31, ChronoUnit.MINUTES));
        // 目标等级跳级 → 422
        assertThatThrownBy(() -> ladderService.demote("INC-L-9", "bob",
                new LadderDemoteRequest(key(), 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        // 目标等于当前等级-1 → 成功
        LadderStateView state = ladderService.demote("INC-L-9", "bob",
                new LadderDemoteRequest(key(), 2));
        assertThat(state.currentLevel()).isEqualTo(2);
    }

    @Test
    void demote_withoutScoreOrAtLevelZero_409() {
        createConfig();
        newIncident("INC-L-10");
        // 无分数 → 409
        assertThatThrownBy(() -> ladderService.demote("INC-L-10", "bob",
                new LadderDemoteRequest(key(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        // 等级 0 → 409
        updateScores("alice", new LadderScoreItem("INC-L-10", 5));
        ((ControllableClock) clock).setInstant(T0.plus(31, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> ladderService.demote("INC-L-10", "bob",
                new LadderDemoteRequest(key(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void demote_closedIncident_422() {
        createConfig();
        newIncident("INC-L-11");
        updateScores("alice", new LadderScoreItem("INC-L-11", 45));
        closeIncident("INC-L-11");
        ((ControllableClock) clock).setInstant(T0.plus(31, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> ladderService.demote("INC-L-11", "bob",
                new LadderDemoteRequest(key(), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
    }

    @Test
    void configRevision_doesNotRewriteHistory() {
        createConfig();
        newIncident("INC-L-12");
        updateScores("alice", new LadderScoreItem("INC-L-12", 45));
        // 配置修订为新版本：历史不回写
        ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(100, 200, 300, 400, 500), 60));
        var before = ladderService.getHistory("INC-L-12").entries();
        assertThat(before).hasSize(1);
        assertThat(before.get(0).configVersion()).isEqualTo(1);
        assertThat(before.get(0).toLevel()).isEqualTo(3);
        // 新配置下 150 分仅 1 级：不自动降级，历史不变
        updateScores("alice", new LadderScoreItem("INC-L-12", 150));
        assertThat(ladderService.getLadder("INC-L-12").currentLevel()).isEqualTo(3);
        assertThat(ladderService.getHistory("INC-L-12").entries()).hasSize(1);
        // 新配置下 550 分达 5 级：追加新历史，记录版本 2，旧记录不变
        updateScores("alice", new LadderScoreItem("INC-L-12", 550));
        var after = ladderService.getHistory("INC-L-12").entries();
        assertThat(after).hasSize(2);
        assertThat(after.get(0)).isEqualTo(before.get(0));
        assertThat(after.get(1).changeType()).isEqualTo("ESCALATE");
        assertThat(after.get(1).fromLevel()).isEqualTo(3);
        assertThat(after.get(1).toLevel()).isEqualTo(5);
        assertThat(after.get(1).configVersion()).isEqualTo(2);
    }

    @Test
    void idempotency_replaySameParams_returnsFirstSnapshot_changeParams_409() {
        createConfig();
        newIncident("INC-L-13");
        String commandKey = key();
        LadderScoreBatchView first = ladderService.updateScores("alice",
                new LadderScoreBatchRequest(commandKey,
                        List.of(new LadderScoreItem("INC-L-13", 45))));
        // 同键同参重放：返回首次完整快照（previousLevel 仍为 0），不重复写历史
        LadderScoreBatchView replay = ladderService.updateScores("alice",
                new LadderScoreBatchRequest(commandKey,
                        List.of(new LadderScoreItem("INC-L-13", 45))));
        assertThat(replay).isEqualTo(first);
        assertThat(replay.results().get(0).previousLevel()).isEqualTo(0);
        assertThat(ladderService.getHistory("INC-L-13").entries()).hasSize(1);
        // 同键改参 → 409
        assertThatThrownBy(() -> ladderService.updateScores("alice",
                new LadderScoreBatchRequest(commandKey,
                        List.of(new LadderScoreItem("INC-L-13", 46)))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
        // 降级幂等：同键重放首次结果，历史不重复
        ((ControllableClock) clock).setInstant(T0.plus(31, ChronoUnit.MINUTES));
        String demoteKey = key();
        LadderStateView demoted = ladderService.demote("INC-L-13", "bob",
                new LadderDemoteRequest(demoteKey, null));
        LadderStateView demotedReplay = ladderService.demote("INC-L-13", "bob",
                new LadderDemoteRequest(demoteKey, null));
        assertThat(demotedReplay).isEqualTo(demoted);
        assertThat(ladderService.getHistory("INC-L-13").entries()).hasSize(2);
        assertThatThrownBy(() -> ladderService.demote("INC-L-13", "bob",
                new LadderDemoteRequest(demoteKey, 2)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(409));
    }

    @Test
    void queries_areReadOnlyAndKeepNullSemantics() {
        createConfig();
        newIncident("INC-L-14");
        // 无分数时快照字段为 null
        LadderStateView empty = ladderService.getLadder("INC-L-14");
        assertThat(empty.riskScore()).isNull();
        assertThat(empty.currentLevel()).isNull();
        assertThat(empty.configVersion()).isNull();
        assertThat(empty.cooldownStartAt()).isNull();
        assertThat(empty.lastOperator()).isNull();
        assertThat(empty.cooldownMinutes()).isEqualTo(30);
        // 只读查询不产生任何状态或历史
        ladderService.getLadder("INC-L-14");
        ladderService.getHistory("INC-L-14");
        ladderService.currentConfig();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_ladder_states", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_ladder_history", Integer.class)).isZero();
        // 事件不存在 → 404
        assertThatThrownBy(() -> ladderService.getLadder("INC-404"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }
}
