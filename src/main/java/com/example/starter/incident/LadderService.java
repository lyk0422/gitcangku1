package com.example.starter.incident;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderVersionCreateRequest;
import com.example.starter.incident.dto.Requests.ScoreBatchRequest;
import com.example.starter.incident.dto.Requests.ScoreUpdateItem;
import com.example.starter.incident.dto.Responses.LadderHistoryView;
import com.example.starter.incident.dto.Responses.LadderScoreView;
import com.example.starter.incident.dto.Responses.LadderVersionView;
import com.example.starter.incident.dto.Responses.LadderView;
import com.example.starter.incident.dto.Responses.ScoreBatchView;
import com.example.starter.incident.dto.Responses.ScoreUpdateResultView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件升级阶梯核心服务：版本化阈值、按风险分数进入 1~5 级阶梯、自动升级与人工降级。
 *
 * <p>等级语义：分数按阈值版本以左闭右开区间定级（[t1,t2) 为 1 级 …… [t5,+∞) 为 5 级，
 * 低于 t1 不在阶梯上）；分数达到更高阈值时自动升级（可跨级），分数下降不会自动降级。
 * 降级必须由不同于当前指挥人的指挥员确认、距最近一次升级已超过冷却分钟数（边界左闭右开，
 * 时刻相等即满足）且逐级下降。
 *
 * <p>并发约定：批量分数更新先按事件键升序依次 SELECT ... FOR UPDATE 锁定全部事件，
 * 先校验所有事件的最终等级再在一个事务内写入；降级以“等级未被并发改动且事件未关闭”为条件
 * 做原子条件更新，并发降级最多一次生效，失败者返回 409。
 *
 * <p>不可变约定：分数证据、阈值版本、升级/降级历史只追加，不更新不删除；
 * 后续阈值版本修订不回写事件等级与历史。
 */
@Service
public class LadderService {

    /** 冷却分钟数上限：一周。 */
    private static final int MAX_COOLDOWN_MINUTES = 7 * 24 * 60;

    private static final String OP_VERSION_CREATE = "ladder_version_create";
    private static final String OP_SCORE_BATCH = "ladder_score_batch";
    private static final String OP_DEMOTE = "ladder_demote";

    private final IncidentRepository incidents;
    private final LadderVersionRepository versions;
    private final LadderStateRepository states;
    private final LadderScoreRepository scores;
    private final LadderHistoryRepository history;
    private final IdempotencyRunner idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LadderService(IncidentRepository incidents, LadderVersionRepository versions,
                         LadderStateRepository states, LadderScoreRepository scores,
                         LadderHistoryRepository history, IdempotencyRunner idempotency,
                         ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.versions = versions;
        this.states = states;
        this.scores = scores;
        this.history = history;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建不可变阈值版本：五个阈值必须严格递增，冷却分钟数必须在 0~10080（含端点）。
     * versionKey 重复返回 409；参数语义非法返回 422。
     */
    @Transactional
    public LadderVersionView createVersion(String actor, LadderVersionCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String versionKey = requireText(req.versionKey(), "versionKey");
        List<Integer> thresholds = validateThresholds(req.thresholds());
        int cooldown = validateCooldown(req.cooldownMinutes());
        String requestHash = fingerprint(Map.of(
                "operation", OP_VERSION_CREATE,
                "operator", actor,
                "versionKey", versionKey,
                "thresholds", thresholds,
                "cooldownMinutes", cooldown));
        return idempotency.run(commandKey, OP_VERSION_CREATE, requestHash, LadderVersionView.class, () -> {
            if (versions.findByKey(versionKey).isPresent()) {
                throw ApiException.conflict("阈值版本键已存在: " + versionKey);
            }
            Instant now = now();
            LadderVersion version = new LadderVersion(0L, versionKey,
                    thresholds.get(0), thresholds.get(1), thresholds.get(2),
                    thresholds.get(3), thresholds.get(4), cooldown, actor, now);
            try {
                versions.insert(version);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("阈值版本键已存在: " + versionKey);
            }
            return toVersionView(versions.findByKey(versionKey).orElseThrow());
        });
    }

    /**
     * 查询全部阈值版本，按创建顺序返回（只读诊断，不修改状态）。
     */
    @Transactional(readOnly = true)
    public List<LadderVersionView> listVersions() {
        return versions.listAll().stream().map(LadderService::toVersionView).toList();
    }

    /**
     * 批量风险分数更新：先校验所有事件最终等级与请求形态，任一失败整批回滚，不留部分结果。
     * 事件键在批内去重后按升序锁定与返回；自动升级、分数证据与等级历史在一个事务内提交。
     */
    @Transactional
    public ScoreBatchView submitScores(String actor, ScoreBatchRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String versionKey = requireText(req.thresholdVersionKey(), "thresholdVersionKey");
        // 请求形态校验（不占键）：非空批、事件键非空、分数不缺失、批内事件键不重复
        List<ScoreUpdateItem> normalized = normalizeBatch(req.updates());
        LadderVersion version = versions.findByKey(versionKey)
                .orElseThrow(() -> ApiException.unprocessable("THRESHOLD_VERSION_NOT_FOUND",
                        "阈值版本不存在: " + versionKey));
        List<String> sortedKeys = normalized.stream().map(ScoreUpdateItem::incidentKey)
                .sorted().toList();
        String requestHash = fingerprint(Map.of(
                "operation", OP_SCORE_BATCH,
                "operator", actor,
                "thresholdVersionKey", versionKey,
                "updates", normalized.stream()
                        .sorted(Comparator.comparing(ScoreUpdateItem::incidentKey))
                        .map(i -> Map.of("incidentKey", i.incidentKey(), "score", i.score()))
                        .toList()));
        return idempotency.run(commandKey, OP_SCORE_BATCH, requestHash, ScoreBatchView.class, () -> {
            Instant committedAt = now();
            // 1) 按事件键升序锁定全部事件；不存在的事件记入整批校验失败
            List<Incident> locked = new ArrayList<>();
            List<BatchFailure> failures = new ArrayList<>();
            for (String key : sortedKeys) {
                var found = incidents.lockByKey(key);
                if (found.isEmpty()) {
                    failures.add(new BatchFailure(key, "INCIDENT_NOT_FOUND", "事件不存在"));
                } else {
                    locked.add(found.get());
                }
            }
            // 2) 校验所有事件最终等级（任一失败整批回滚）
            for (Incident incident : locked) {
                if (incident.status() == IncidentStatus.CLOSED) {
                    failures.add(new BatchFailure(incident.incidentKey(), "INCIDENT_CLOSED",
                            "事件已关闭，禁止分数更新"));
                }
            }
            if (!failures.isEmpty()) {
                // 全部因事件关闭失败时按 409 裁决，其余语义失败按 422；明细逐条区分原因
                boolean allClosed = failures.stream()
                        .allMatch(f -> "INCIDENT_CLOSED".equals(f.reason()));
                List<Map<String, String>> details = failures.stream()
                        .sorted(Comparator.comparing(BatchFailure::incidentKey))
                        .map(f -> Map.of("incidentKey", f.incidentKey(), "reason", f.reason(),
                                "message", f.message()))
                        .toList();
                if (allClosed) {
                    throw ApiException.conflict("存在已关闭事件，批量分数更新被拒绝", details);
                }
                throw ApiException.unprocessable("BATCH_VALIDATION_FAILED",
                        "批量更新校验失败，整批回滚", details);
            }
            // 3) 全部通过后在同一事务内写入证据、状态与历史
            Map<String, Incident> incidentByKey = new HashMap<>();
            for (Incident incident : locked) {
                incidentByKey.put(incident.incidentKey(), incident);
            }
            List<ScoreUpdateResultView> results = new ArrayList<>();
            for (ScoreUpdateItem item : normalized.stream()
                    .sorted(Comparator.comparing(ScoreUpdateItem::incidentKey)).toList()) {
                results.add(applyOneScore(incidentByKey.get(item.incidentKey()), item.score(),
                        version, actor, committedAt));
            }
            return new ScoreBatchView(versionKey, results);
        });
    }

    /**
     * 人工降级：操作人必须不同于当前指挥人；事件须在阶梯上且等级高于 1；
     * 当前时刻须已达到“最近升级时刻 + 冷却分钟数”（时刻相等即满足）。
     * 条件更新保证并发降级最多一次生效，事件在期间被关闭或等级被改动则返回 409。
     */
    @Transactional
    public LadderView demote(String incidentKey, String actor, LadderDemoteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireText(actor, "X-Actor-Id");
        // 加锁前读取等级快照作为本次降级的判定基准（并发去重令牌）。
        // 并发降级中后提交者在事件行锁内会发现等级已被先提交者改变而失败（最多一次生效）；
        // 此处尚未写入幂等占位，失败不占键。
        Incident preIncident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        LadderState preState = states.findByIncident(preIncident.id())
                .orElseThrow(() -> ApiException.conflict("事件尚未进入升级阶梯，不能降级: "
                        + incidentKey));
        int snapshotLevel = preState.currentLevel();
        String requestHash = fingerprint(Map.of(
                "operation", OP_DEMOTE,
                "operator", actor,
                "incidentKey", incidentKey));
        return idempotency.run(commandKey, OP_DEMOTE, requestHash, LadderView.class, () -> {
            // 事务内锁定事件行，保证与分数更新、关闭按提交顺序串行裁决
            Incident incident = incidents.lockByKey(incidentKey)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
            LadderState state = states.findByIncident(incident.id()).orElseThrow();
            if (state.currentLevel() != snapshotLevel) {
                // 已有一个并发降级先提交（或等级被并发分数更新改动），本次不生效
                throw ApiException.conflict("事件等级已被并发操作改变，本次降级未生效");
            }
            if (incident.status() == IncidentStatus.CLOSED) {
                throw ApiException.conflict("事件已关闭，禁止降级");
            }
            if (incident.commander() == null || incident.commander().equals(actor)) {
                throw ApiException.conflict("降级必须由不同于当前指挥人的指挥员确认；当前指挥人: "
                        + (incident.commander() == null ? "(无)" : incident.commander()));
            }
            if (state.currentLevel() <= 1) {
                throw ApiException.conflict("已处于最低等级 1，不能继续降级");
            }
            LadderVersion version = cooldownVersion(history.listByIncident(incident.id()), state);
            Instant now = now();
            if (state.lastPromotedAt() != null && version != null) {
                Instant cooldownDeadline = state.lastPromotedAt()
                        .plus(version.cooldownMinutes(), ChronoUnit.MINUTES);
                if (now.isBefore(cooldownDeadline)) {
                    long elapsed = Math.max(0, ChronoUnit.MINUTES.between(state.lastPromotedAt(), now));
                    long remainingSeconds = Math.max(0, ChronoUnit.SECONDS.between(now, cooldownDeadline));
                    long remaining = (remainingSeconds + 59) / 60;
                    throw ApiException.conflict("距最近升级尚未超过冷却时间，不能降级", Map.of(
                            "elapsedMinutes", elapsed,
                            "requiredCooldownMinutes", version.cooldownMinutes(),
                            "remainingMinutes", remaining));
                }
            }
            // 原子条件更新：仅当等级仍为快照等级且事件未关闭时降一级，并发竞争最多一次生效
            int updated = states.demoteIfSameLevelAndOpen(incident.id(), snapshotLevel, now);
            if (updated == 0) {
                throw ApiException.conflict("事件等级或状态已被并发操作改变，降级未生效");
            }
            history.insert(new LadderHistory(0L, incident.id(), LadderChangeType.DEMOTE,
                    snapshotLevel, snapshotLevel - 1, state.lastScore(),
                    state.thresholdVersionId(), actor, state.lastPromotedAt(), now));
            return buildView(incidents.lockByKey(incidentKey).orElseThrow());
        });
    }

    /**
     * 查询事件升级阶梯明细：当前等级、冷却状态、全部等级变更历史与不可变分数证据。
     * 只读，不修改状态，排序稳定（历史与证据均按落库顺序）。
     */
    @Transactional(readOnly = true)
    public LadderView getLadder(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        return buildView(incident);
    }

    /**
     * 单个事件的分数落库与自动升级：仅当分数对应等级高于当前等级时升级（可跨级，只升不降）。
     */
    private ScoreUpdateResultView applyOneScore(Incident incident, int score, LadderVersion version,
                                                String actor, Instant now) {
        Integer requiredLevel = version.levelOf(score);
        LadderState existing = states.findByIncident(incident.id()).orElse(null);
        Integer previousLevel = existing == null ? null : existing.currentLevel();

        scores.insert(new LadderScore(0L, incident.id(), score, version.id(), requiredLevel,
                actor, now, now));

        boolean changed;
        Integer actualLevel;
        if (existing == null) {
            if (requiredLevel == null) {
                changed = false;
                actualLevel = null;
            } else {
                states.insert(new LadderState(0L, incident.id(), requiredLevel, score, version.id(),
                        now, now, now));
                history.insert(new LadderHistory(0L, incident.id(), LadderChangeType.PROMOTE,
                        null, requiredLevel, score, version.id(), actor, now, now));
                changed = true;
                actualLevel = requiredLevel;
            }
        } else {
            actualLevel = existing.currentLevel();
            if (requiredLevel != null && requiredLevel > existing.currentLevel()) {
                states.updateLevelAndPromotion(incident.id(), requiredLevel, score, version.id(),
                        now, now);
                history.insert(new LadderHistory(0L, incident.id(), LadderChangeType.PROMOTE,
                        existing.currentLevel(), requiredLevel, score, version.id(), actor, now, now));
                actualLevel = requiredLevel;
                changed = true;
            } else {
                states.updateScore(incident.id(), score, version.id(), now);
                changed = false;
            }
        }
        int levelDelta;
        if (actualLevel == null && requiredLevel == null) {
            levelDelta = 0;
        } else if (actualLevel == null) {
            levelDelta = -requiredLevel;
        } else if (requiredLevel == null) {
            levelDelta = actualLevel;
        } else {
            levelDelta = actualLevel - requiredLevel;
        }
        return new ScoreUpdateResultView(incident.incidentKey(), score, previousLevel,
                requiredLevel, actualLevel, levelDelta, changed);
    }

    private LadderView buildView(Incident incident) {
        LadderState state = states.findByIncident(incident.id()).orElse(null);
        List<LadderHistory> histories = history.listByIncident(incident.id());
        List<LadderScore> scoreRows = scores.listByIncident(incident.id());

        Map<Long, String> versionKeys = new HashMap<>();
        for (LadderScore row : scoreRows) {
            versionKeys.putIfAbsent(row.thresholdVersionId(),
                    versions.findById(row.thresholdVersionId()).map(LadderVersion::versionKey).orElse(null));
        }
        for (LadderHistory row : histories) {
            if (row.thresholdVersionId() != null) {
                versionKeys.putIfAbsent(row.thresholdVersionId(),
                        versions.findById(row.thresholdVersionId())
                                .map(LadderVersion::versionKey).orElse(null));
            }
        }

        List<LadderHistoryView> historyViews = histories.stream()
                .map(h -> new LadderHistoryView(h.changeType().name(), h.fromLevel(), h.toLevel(),
                        h.score(),
                        h.thresholdVersionId() == null ? null : versionKeys.get(h.thresholdVersionId()),
                        h.operator(), h.cooldownSince(), h.occurredAt()))
                .toList();
        List<LadderScoreView> scoreViews = scoreRows.stream()
                .map(s -> new LadderScoreView(s.score(), versionKeys.get(s.thresholdVersionId()),
                        s.evaluatedLevel(), s.submittedBy(), s.occurredAt()))
                .toList();

        Instant now = now();
        boolean demoteReady = false;
        long remainingMinutes = 0L;
        String currentVersionKey = null;
        if (state != null) {
            currentVersionKey = versionKeys.get(state.thresholdVersionId());
            LadderVersion cooldownVersion = cooldownVersion(histories, state);
            if (state.lastPromotedAt() != null && cooldownVersion != null) {
                Instant deadline = state.lastPromotedAt()
                        .plus(cooldownVersion.cooldownMinutes(), ChronoUnit.MINUTES);
                if (now.isBefore(deadline)) {
                    long remainingSeconds = Math.max(0, ChronoUnit.SECONDS.between(now, deadline));
                    remainingMinutes = (remainingSeconds + 59) / 60;
                } else {
                    demoteReady = state.currentLevel() > 1;
                }
            } else if (state.currentLevel() > 1) {
                demoteReady = true;
            }
        }
        return new LadderView(incident.incidentKey(), state != null,
                state == null ? null : state.currentLevel(),
                state == null ? null : state.lastScore(), currentVersionKey,
                state == null ? null : state.lastPromotedAt(),
                demoteReady, remainingMinutes, historyViews, scoreViews);
    }

    /**
     * 冷却时长取最近一次升级（冷却起点）当时使用的阈值版本；无升级历史时退回当前状态引用版本。
     */
    private LadderVersion cooldownVersion(List<LadderHistory> histories, LadderState state) {
        for (int i = histories.size() - 1; i >= 0; i--) {
            LadderHistory entry = histories.get(i);
            if (entry.changeType() == LadderChangeType.PROMOTE && entry.thresholdVersionId() != null) {
                return versions.findById(entry.thresholdVersionId()).orElse(null);
            }
        }
        return versions.findById(state.thresholdVersionId()).orElse(null);
    }

    /**
     * 规范化批量请求：非空、事件键非空、分数不缺失、批内不允许同一事件出现多次。
     * 失败均为请求形态问题，返回 422 且不占用 commandKey。
     */
    private List<ScoreUpdateItem> normalizeBatch(List<ScoreUpdateItem> updates) {
        if (updates == null || updates.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_BATCH", "updates 不能为空批");
        }
        List<ScoreUpdateItem> normalized = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ScoreUpdateItem item : updates) {
            if (item == null) {
                throw ApiException.unprocessable("INVALID_BATCH_ITEM", "updates 存在空条目");
            }
            String incidentKey = item.incidentKey() == null ? null : item.incidentKey().strip();
            if (incidentKey == null || incidentKey.isEmpty()) {
                throw ApiException.unprocessable("INCIDENT_KEY_MISSING", "updates 存在缺失 incidentKey 的条目");
            }
            if (item.score() == null) {
                throw ApiException.unprocessable("SCORE_MISSING",
                        "事件 " + incidentKey + " 的 score 缺失");
            }
            if (seen.contains(incidentKey)) {
                if (!duplicated.contains(incidentKey)) {
                    duplicated.add(incidentKey);
                }
            } else {
                seen.add(incidentKey);
                normalized.add(new ScoreUpdateItem(incidentKey, item.score()));
            }
        }
        if (!duplicated.isEmpty()) {
            throw ApiException.unprocessable("DUPLICATE_INCIDENT_IN_BATCH",
                    "同一事件在一批分数更新中只能出现一次", List.copyOf(duplicated));
        }
        return normalized;
    }

    private static List<Integer> validateThresholds(List<Integer> thresholds) {
        if (thresholds == null || thresholds.size() != 5 || thresholds.stream().anyMatch(t -> t == null)) {
            throw ApiException.unprocessable("INVALID_THRESHOLDS", "thresholds 必须恰好包含 5 个非空整数阈值");
        }
        for (int i = 1; i < thresholds.size(); i++) {
            if (thresholds.get(i - 1) >= thresholds.get(i)) {
                throw ApiException.unprocessable("INVALID_THRESHOLDS",
                        "阈值必须严格递增: " + thresholds);
            }
        }
        return List.copyOf(thresholds);
    }

    private static int validateCooldown(Integer cooldownMinutes) {
        if (cooldownMinutes == null) {
            throw ApiException.unprocessable("INVALID_COOLDOWN", "cooldownMinutes 缺失");
        }
        if (cooldownMinutes < 0 || cooldownMinutes > MAX_COOLDOWN_MINUTES) {
            throw ApiException.unprocessable("INVALID_COOLDOWN",
                    "cooldownMinutes 必须在 0~" + MAX_COOLDOWN_MINUTES + " 之间，实际值: "
                            + cooldownMinutes);
        }
        return cooldownMinutes;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    private static LadderVersionView toVersionView(LadderVersion version) {
        return new LadderVersionView(version.versionKey(),
                List.of(version.threshold1(), version.threshold2(), version.threshold3(),
                        version.threshold4(), version.threshold5()),
                version.cooldownMinutes(), version.createdBy(), version.createdAt());
    }

    /**
     * 用操作者、操作类型、规范化集合及全部影响结果字段构造结构化指纹：
     * 先组装有序 Map/List，再序列化为规范化 JSON 后取 SHA-256，避免未转义分隔符拼接歧义。
     */
    private String fingerprint(Map<String, Object> structured) {
        Map<String, Object> canonical = new LinkedHashMap<>(structured);
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指纹序列化失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 批量校验失败明细（仅服务内部传递）。
     */
    private record BatchFailure(String incidentKey, String reason, String message) {
    }
}
