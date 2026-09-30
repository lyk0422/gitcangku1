package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.incident.dto.Requests.LadderConfigRequest;
import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderScoreBatchRequest;
import com.example.starter.incident.dto.Requests.LadderScoreItem;
import com.example.starter.incident.dto.Responses.CooldownDetail;
import com.example.starter.incident.dto.Responses.LadderConfigView;
import com.example.starter.incident.dto.Responses.LadderHistoryEntryView;
import com.example.starter.incident.dto.Responses.LadderHistoryView;
import com.example.starter.incident.dto.Responses.LadderScoreBatchView;
import com.example.starter.incident.dto.Responses.LadderScoreResultView;
import com.example.starter.incident.dto.Responses.LadderStateView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件升级阶梯服务：事件按风险分数进入 1~5 级升级阶梯，阈值配置版本化。
 * 分数达到更高阈值时自动提升（可跨级）；降级必须逐级、由与最近一次等级变更
 * 操作人不同的指挥员确认，且距冷却起点（最近一次等级变更时刻）已超过冷却分钟数，
 * 冷却区间 [冷却起点, 起点+冷却分钟数) 左闭右开。
 * 等级历史只追加不回写；后续配置修订不影响已记录的历史快照。
 * 并发约定：写路径先 SELECT ... FOR UPDATE 锁定事件行（批量按事件键排序加锁），
 * 同一事件并发降级由冷却起点重置保证最多一次生效；所有写操作按事务提交顺序裁决。
 */
@Service
public class LadderService {

    /** 风险分数合法下限（含）。 */
    static final int MIN_SCORE = 0;

    /** 风险分数合法上限（含）。 */
    static final int MAX_SCORE = 1_000_000;

    /** 降级冷却分钟数合法下限（含）。 */
    static final int MIN_COOLDOWN_MINUTES = 1;

    /** 降级冷却分钟数合法上限（含），即 7 天。 */
    static final int MAX_COOLDOWN_MINUTES = 10_080;

    private final IncidentRepository incidents;
    private final LadderRepository ladder;
    private final CommandKeyExecutor executor;
    private final Clock clock;

    public LadderService(IncidentRepository incidents, LadderRepository ladder,
                         CommandKeyExecutor executor, Clock clock) {
        this.incidents = incidents;
        this.ladder = ladder;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * 创建阈值配置新版本：阈值必须恰好 5 个且严格递增（422），冷却分钟数 1~10080（422）。
     * 版本号在事务内取当前最大版本+1，唯一约束兜底并发冲突（409）。
     */
    @Transactional
    public LadderConfigView createConfig(String actor, LadderConfigRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String operator = requireText(actor, "actor");
        List<Integer> thresholds = req.thresholds();
        if (thresholds == null || thresholds.size() != LadderConfig.LEVEL_COUNT
                || thresholds.stream().anyMatch(java.util.Objects::isNull)) {
            throw ApiException.badRequest("thresholds 必须恰好包含 " + LadderConfig.LEVEL_COUNT + " 个阈值");
        }
        List<String> parts = new ArrayList<>();
        parts.add(operator);
        thresholds.forEach(t -> parts.add(String.valueOf(t)));
        parts.add(String.valueOf(req.cooldownMinutes()));
        return executor.run(commandKey, "ladder_config",
                CommandKeyExecutor.fingerprint(parts.toArray(new String[0])),
                LadderConfigView.class, () -> {
                    validateThresholds(thresholds);
                    int cooldown = validateCooldown(req.cooldownMinutes());
                    int version = ladder.findLatestConfig()
                            .map(c -> c.configVersion() + 1).orElse(1);
                    Instant now = clock.instant();
                    LadderConfig config = new LadderConfig(0L, version, thresholds, cooldown,
                            operator, now);
                    try {
                        ladder.insertConfig(config);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("配置版本并发冲突，请更换 commandKey 重试");
                    }
                    return toConfigView(ladder.findLatestConfig().orElseThrow());
                });
    }

    /**
     * 查询当前（最新版本）阈值配置；尚无配置返回 404。只读，不修改状态。
     */
    @Transactional(readOnly = true)
    public LadderConfigView currentConfig() {
        return toConfigView(ladder.findLatestConfig()
                .orElseThrow(() -> ApiException.notFound("尚未创建升级阶梯阈值配置")));
    }

    /**
     * 批量更新风险分数：先锁定并校验全部事件（存在、未关闭、分数齐全），
     * 计算最终等级全部通过后，再在一个事务内统一提交；任一失败整批回滚。
     * 分数达到更高阈值时自动提升（可跨级）；分数下降不自动降级。
     */
    @Transactional
    public LadderScoreBatchView updateScores(String actor, LadderScoreBatchRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String operator = requireText(actor, "actor");
        List<LadderScoreItem> items = normalizeUpdates(req.updates());
        List<String> parts = new ArrayList<>();
        parts.add(operator);
        for (LadderScoreItem item : items) {
            parts.add(item.incidentKey());
            parts.add(String.valueOf(item.score()));
        }
        return executor.run(commandKey, "ladder_scores",
                CommandKeyExecutor.fingerprint(parts.toArray(new String[0])),
                LadderScoreBatchView.class, () -> {
                    LadderConfig config = ladder.findLatestConfig()
                            .orElseThrow(() -> ApiException.conflict("尚未创建升级阶梯阈值配置"));
                    // 先按事件键排序锁定全部事件并完成校验，任何写入之前失败即整批回滚
                    List<Incident> locked = new ArrayList<>();
                    for (LadderScoreItem item : items) {
                        Incident incident = incidents.lockByKey(item.incidentKey())
                                .orElseThrow(() -> ApiException.notFound(
                                        "事件不存在: " + item.incidentKey()));
                        if (incident.status() == IncidentStatus.CLOSED) {
                            throw ApiException.illegalTransition(
                                    "事件已关闭，不能更新风险分数: " + item.incidentKey());
                        }
                        locked.add(incident);
                    }
                    // 全部校验通过后统一写入：状态、历史与幂等响应同事务提交
                    Instant now = clock.instant();
                    List<LadderScoreResultView> results = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) {
                        results.add(applyScore(locked.get(i), items.get(i).score(), config,
                                operator, now));
                    }
                    return new LadderScoreBatchView(config.configVersion(), results);
                });
    }

    /**
     * 降级一级：事件未关闭（422）；须已入阶梯且当前等级 ≥ 1（409）；
     * 操作人须与最近一次等级变更操作人不同（409）；冷却须已过（409，附要求/实际/差额明细）；
     * targetLevel 提供时必须等于当前等级-1（422，降级不得跳过层级）。
     */
    @Transactional
    public LadderStateView demote(String incidentKey, String actor, LadderDemoteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String operator = requireText(actor, "actor");
        String key = requireText(incidentKey, "incidentKey");
        Incident incident = incidents.lockByKey(key)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + key));
        Integer targetLevel = req.targetLevel();
        return executor.run(commandKey, "ladder_demote",
                CommandKeyExecutor.fingerprint(key, operator, String.valueOf(targetLevel)),
                LadderStateView.class, () -> {
                    if (incident.status() == IncidentStatus.CLOSED) {
                        throw ApiException.illegalTransition("事件已关闭，不能降级");
                    }
                    LadderConfig config = ladder.findLatestConfig()
                            .orElseThrow(() -> ApiException.conflict("尚未创建升级阶梯阈值配置"));
                    LadderState state = ladder.findState(incident.id())
                            .orElseThrow(() -> ApiException.conflict(
                                    "事件缺少风险分数，尚未进入升级阶梯"));
                    int current = state.currentLevel();
                    if (current <= 0) {
                        throw ApiException.conflict("当前等级为 0，无法降级");
                    }
                    if (targetLevel != null && targetLevel != current - 1) {
                        throw ApiException.unprocessable("降级不得跳过层级：当前 " + current
                                + " 级，目标必须为 " + (current - 1) + " 级");
                    }
                    if (operator.equals(state.lastOperator())) {
                        throw ApiException.conflict("降级必须由不同指挥员确认，最近等级变更操作人为 "
                                + state.lastOperator());
                    }
                    Instant now = clock.instant();
                    Instant cooldownEnd = state.cooldownStartAt()
                            .plus(config.cooldownMinutes(), ChronoUnit.MINUTES);
                    if (now.isBefore(cooldownEnd)) {
                        long required = config.cooldownMinutes();
                        long elapsed = ChronoUnit.MINUTES.between(state.cooldownStartAt(), now);
                        throw ApiException.conflict("降级冷却未结束：要求 " + required + " 分钟，已等待 "
                                        + elapsed + " 分钟，剩余 " + (required - elapsed) + " 分钟",
                                new CooldownDetail(required, elapsed, required - elapsed));
                    }
                    ladder.updateState(new LadderState(state.id(), incident.id(), state.riskScore(),
                            current - 1, config.configVersion(), now, operator,
                            state.createdAt(), now));
                    ladder.insertHistory(new LadderHistoryEntry(0L, incident.id(),
                            LadderChangeType.DEESCALATE, current, current - 1, state.riskScore(),
                            config.configVersion(), operator, now, now));
                    return toStateView(key, ladder.findState(incident.id()).orElseThrow(), config);
                });
    }

    /**
     * 查询事件阶梯当前状态与冷却诊断；事件无分数时各快照字段为 null。只读，不修改状态。
     */
    @Transactional(readOnly = true)
    public LadderStateView getLadder(String incidentKey) {
        String key = requireText(incidentKey, "incidentKey");
        Incident incident = incidents.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + key));
        LadderState state = ladder.findState(incident.id()).orElse(null);
        LadderConfig config = ladder.findLatestConfig().orElse(null);
        return toStateView(key, state, config);
    }

    /**
     * 查询事件阶梯等级变更历史，按发生顺序返回。只读，不修改状态。
     */
    @Transactional(readOnly = true)
    public LadderHistoryView getHistory(String incidentKey) {
        String key = requireText(incidentKey, "incidentKey");
        Incident incident = incidents.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + key));
        List<LadderHistoryEntryView> entries = ladder.listHistory(incident.id()).stream()
                .map(e -> new LadderHistoryEntryView(e.id(), e.changeType().name(), e.fromLevel(),
                        e.toLevel(), e.riskScore(), e.configVersion(), e.actor(), e.occurredAt()))
                .toList();
        return new LadderHistoryView(key, entries);
    }

    /**
     * 应用单事件分数更新：计算最终等级（只升不降），更新状态行，
     * 等级提升时追加 ESCALATE 历史并重置冷却起点与操作人。
     */
    private LadderScoreResultView applyScore(Incident incident, int score, LadderConfig config,
                                             String operator, Instant now) {
        LadderState state = ladder.findState(incident.id()).orElse(null);
        int previous = state == null ? 0 : state.currentLevel();
        int target = config.levelFor(score);
        boolean escalated = target > previous;
        int newLevel = Math.max(target, previous);
        if (state == null) {
            ladder.insertState(new LadderState(0L, incident.id(), score, newLevel,
                    config.configVersion(), now, operator, now, now));
        } else if (escalated) {
            ladder.updateState(new LadderState(state.id(), incident.id(), score, newLevel,
                    config.configVersion(), now, operator, state.createdAt(), now));
        } else {
            // 不自动降级：仅更新分数与定级配置版本，冷却起点与操作人保持不变
            ladder.updateState(new LadderState(state.id(), incident.id(), score, newLevel,
                    config.configVersion(), state.cooldownStartAt(), state.lastOperator(),
                    state.createdAt(), now));
        }
        if (escalated) {
            ladder.insertHistory(new LadderHistoryEntry(0L, incident.id(),
                    LadderChangeType.ESCALATE, previous, newLevel, score, config.configVersion(),
                    operator, now, now));
        }
        Integer nextThreshold = config.nextThreshold(newLevel);
        return new LadderScoreResultView(incident.incidentKey(), score, previous, newLevel,
                escalated, nextThreshold, nextThreshold == null ? null : nextThreshold - score);
    }

    /**
     * 规范化批量分数更新：逐项校验（事件键非空、不重复、分数非缺失且在范围内），
     * 并按事件键排序，保证指纹与加锁顺序稳定。
     */
    private static List<LadderScoreItem> normalizeUpdates(List<LadderScoreItem> updates) {
        if (updates == null || updates.isEmpty()) {
            throw ApiException.badRequest("updates 不能为空");
        }
        List<LadderScoreItem> normalized = new ArrayList<>(updates.size());
        Set<String> seen = new HashSet<>();
        for (LadderScoreItem item : updates) {
            if (item == null) {
                throw ApiException.badRequest("updates 含空项");
            }
            String key = requireText(item.incidentKey(), "incidentKey");
            if (!seen.add(key)) {
                throw ApiException.badRequest("updates 含重复事件: " + key);
            }
            if (item.score() == null) {
                throw ApiException.unprocessable("分数缺失: " + key);
            }
            if (item.score() < MIN_SCORE || item.score() > MAX_SCORE) {
                throw ApiException.unprocessable("分数超出范围 [" + MIN_SCORE + "," + MAX_SCORE
                        + "]: " + key);
            }
            normalized.add(new LadderScoreItem(key, item.score()));
        }
        normalized.sort(Comparator.comparing(LadderScoreItem::incidentKey));
        return normalized;
    }

    private static void validateThresholds(List<Integer> thresholds) {
        for (Integer threshold : thresholds) {
            if (threshold < MIN_SCORE || threshold > MAX_SCORE) {
                throw ApiException.unprocessable("阈值超出范围 [" + MIN_SCORE + "," + MAX_SCORE
                        + "]: " + threshold);
            }
        }
        for (int i = 1; i < thresholds.size(); i++) {
            if (thresholds.get(i) <= thresholds.get(i - 1)) {
                throw ApiException.unprocessable("阈值必须严格递增: " + thresholds);
            }
        }
    }

    private static int validateCooldown(Integer cooldownMinutes) {
        if (cooldownMinutes == null || cooldownMinutes < MIN_COOLDOWN_MINUTES
                || cooldownMinutes > MAX_COOLDOWN_MINUTES) {
            throw ApiException.unprocessable("冷却分钟数必须在 " + MIN_COOLDOWN_MINUTES + "~"
                    + MAX_COOLDOWN_MINUTES + " 之间: " + cooldownMinutes);
        }
        return cooldownMinutes;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private static LadderConfigView toConfigView(LadderConfig config) {
        return new LadderConfigView(config.configVersion(), config.thresholds(),
                config.cooldownMinutes(), config.createdBy(), config.createdAt());
    }

    /**
     * 组装阶梯状态视图：无状态行时快照字段为 null；
     * cooldownRemainingMinutes 按查询时刻计算（0 表示冷却已过）。
     */
    private LadderStateView toStateView(String incidentKey, LadderState state, LadderConfig config) {
        if (state == null) {
            return new LadderStateView(incidentKey, null, null, null, null,
                    config == null ? null : config.cooldownMinutes(), null, null, null);
        }
        Long remaining = null;
        if (config != null) {
            Instant cooldownEnd = state.cooldownStartAt()
                    .plus(config.cooldownMinutes(), ChronoUnit.MINUTES);
            remaining = Math.max(0L, ChronoUnit.MINUTES.between(clock.instant(), cooldownEnd));
        }
        return new LadderStateView(incidentKey, state.riskScore(), state.currentLevel(),
                state.configVersion(), state.cooldownStartAt(),
                config == null ? null : config.cooldownMinutes(), remaining,
                state.lastOperator(), state.updatedAt());
    }
}
