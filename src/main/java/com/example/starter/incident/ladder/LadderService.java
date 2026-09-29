package com.example.starter.incident.ladder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.CommandKeyRecord;
import com.example.starter.incident.CommandKeyRepository;
import com.example.starter.incident.Incident;
import com.example.starter.incident.IncidentRepository;
import com.example.starter.incident.IncidentStatus;
import com.example.starter.incident.ladder.dto.LadderRequests.BatchScoreItem;
import com.example.starter.incident.ladder.dto.LadderRequests.BatchScoreRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.DemoteRequest;
import com.example.starter.incident.ladder.dto.LadderRequests.PublishVersionRequest;
import com.example.starter.incident.ladder.dto.LadderResponses.BatchScoreResponse;
import com.example.starter.incident.ladder.dto.LadderResponses.BatchScoreResultItem;
import com.example.starter.incident.ladder.dto.LadderResponses.DemoteResponse;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderDetailView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderDiagnosticsView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderHistoryItemView;
import com.example.starter.incident.ladder.dto.LadderResponses.LadderHistoryView;
import com.example.starter.incident.ladder.dto.LadderResponses.ThresholdVersionView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 风险分数驱动的 1~5 级升级阶梯核心服务。
 *
 * <p>等级裁决：L(score) = max({1} ∪ {i | score ≥ threshold_i})，阈值来自当前生效的不可变版本；
 * 分数达到更高阈值时在分数更新内自动提升（可跨多级），分数下降不自动降级，
 * 降级只能由与最近升级操作人不同的指挥员确认、逐级下降，且距最近升级必须严格超过冷却分钟数
 * （区间左闭右开：生效时刻 t 满足 t - 冷却起点 &gt; 冷却时长，边界相等不允许）。
 *
 * <p>并发约定：所有写路径在同一事务内先锁相关行——批量更新按"版本指针行 → 事件 id 升序 →
 * 阶梯态行"的固定顺序加锁，降级按"事件行 → 阶梯态行"加锁；降级以当前等级为条件更新，
 * 同一事件并发降级最多一次生效。
 *
 * <p>幂等约定：commandKey 全局唯一，指纹由操作者、操作类型、规范化集合及全部影响结果字段
 * 序列化为转义 JSON 后 SHA-256 得到（不使用分隔符拼接）；同键同参重放首次完整响应快照，
 * 同键异参 409，业务失败随事务回滚不占用命令键。
 */
@Service
public class LadderService {

    private static final String OP_PUBLISH = "ladder_publish";
    private static final String OP_BATCH_SCORE = "ladder_score_batch";
    private static final String OP_DEMOTE = "ladder_demote";

    private final IncidentRepository incidents;
    private final ThresholdVersionRepository versions;
    private final LadderRepository ladder;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LadderService(IncidentRepository incidents, ThresholdVersionRepository versions,
                         LadderRepository ladder, CommandKeyRepository commandKeys,
                         ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.versions = versions;
        this.ladder = ladder;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 纯函数：按阈值版本计算分数对应等级。thresholds 固定 5 个、非负严格递增。
     */
    public static int levelFor(List<Long> thresholds, long score) {
        int level = 1;
        for (int i = 0; i < ThresholdVersion.LEVELS; i++) {
            if (score >= thresholds.get(i)) {
                level = i + 1;
            }
        }
        return level;
    }

    /**
     * 发布不可变阈值版本并原子切换当前生效指针；versionKey 重复返回 409，
     * 阈值非严格递增或冷却分钟数越界返回 422。发布不回写任何事件等级与历史。
     */
    @Transactional
    public ThresholdVersionView publishVersion(String actor, PublishVersionRequest req) {
        String operator = requireText(actor, "X-Actor-Id");
        String commandKey = requireText(req.commandKey(), "commandKey");
        String versionKey = requireText(req.versionKey(), "versionKey");
        List<Long> thresholds = validateThresholds(req.thresholds());
        int cooldown = validateCooldown(req.cooldownMinutes());
        String note = req.note() == null ? null : req.note().strip();
        Map<String, Object> fp = new TreeMap<>();
        fp.put("operation", OP_PUBLISH);
        fp.put("actor", operator);
        fp.put("versionKey", versionKey);
        fp.put("thresholds", new ArrayList<>(thresholds));
        fp.put("cooldownMinutes", cooldown);
        fp.put("note", note);
        return runIdempotent(commandKey, OP_PUBLISH, fingerprint(fp), ThresholdVersionView.class,
                () -> {
                    if (versions.findByKey(versionKey).isPresent()) {
                        throw ApiException.conflict("阈值版本键已存在: " + versionKey);
                    }
                    Instant now = now();
                    ThresholdVersion version = new ThresholdVersion(0L, versionKey,
                            List.copyOf(thresholds), cooldown, note, operator, now);
                    long id;
                    try {
                        id = versions.insert(version);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("阈值版本键已存在: " + versionKey);
                    }
                    versions.pointCurrent(id, now);
                    return toVersionView(new ThresholdVersion(id, versionKey,
                            List.copyOf(thresholds), cooldown, note, operator, now));
                });
    }

    /**
     * 批量风险分数更新：先锁全部目标事件并校验完整后态（存在性、非 CLOSED、各自最终等级），
     * 任一失败整批回滚；全部合法后在同一事务内写当前态与不可变历史。
     * 无阶梯态行的事件按首次登记处理（INIT），分数下降不自动降级。
     */
    @Transactional
    public BatchScoreResponse batchUpdateScores(String actor, BatchScoreRequest req) {
        String operator = requireText(actor, "X-Actor-Id");
        String commandKey = requireText(req.commandKey(), "commandKey");
        List<BatchScoreItem> rawItems = req.items();
        if (rawItems == null || rawItems.isEmpty()) {
            throw ApiException.badRequest("items 不能为空");
        }
        List<BatchScoreItem> items = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (BatchScoreItem item : rawItems) {
            if (item == null) {
                throw ApiException.badRequest("items 含空元素");
            }
            String incidentKey = requireText(item.incidentKey(), "incidentKey");
            Long score = item.score();
            if (score == null) {
                throw ApiException.badRequest("score 不能为空: " + incidentKey);
            }
            if (score < 0) {
                throw ApiException.badRequest("score 不能为负: " + incidentKey);
            }
            if (seen.put(incidentKey, Boolean.TRUE) != null) {
                throw ApiException.badRequest("items 含重复事件: " + incidentKey);
            }
            items.add(new BatchScoreItem(incidentKey, score));
        }
        // 规范化集合：指纹内按事件键排序，集合顺序不影响语义
        List<Map<String, Object>> canonicalItems = items.stream()
                .sorted(Comparator.comparing(BatchScoreItem::incidentKey))
                .map(i -> {
                    Map<String, Object> m = new TreeMap<>();
                    m.put("incidentKey", i.incidentKey());
                    m.put("score", i.score());
                    return m;
                })
                .toList();
        Map<String, Object> fp = new TreeMap<>();
        fp.put("operation", OP_BATCH_SCORE);
        fp.put("actor", operator);
        fp.put("items", canonicalItems);
        return runIdempotent(commandKey, OP_BATCH_SCORE, fingerprint(fp), BatchScoreResponse.class,
                () -> applyBatch(operator, commandKey, items));
    }

    private BatchScoreResponse applyBatch(String operator, String commandKey,
                                          List<BatchScoreItem> items) {
        // 锁当前版本指针行，整批裁决使用同一确定版本，且与版本发布串行
        ThresholdVersion version = versions.lockCurrent()
                .orElseThrow(() -> ApiException.conflict("尚未发布任何阈值版本，无法裁决等级"));

        // 第一轮：按事件键取出并按 id 升序加锁，校验完整后态，只计算不落库
        Map<String, Incident> incidentByKey = new HashMap<>();
        for (BatchScoreItem item : items) {
            Incident incident = incidents.findByKey(item.incidentKey())
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + item.incidentKey()));
            incidentByKey.put(item.incidentKey(), incident);
        }
        List<Long> orderedIds = incidentByKey.values().stream()
                .map(Incident::id)
                .sorted()
                .toList();
        List<Incident> locked = incidents.lockByIdsOrdered(orderedIds);
        if (locked.size() != orderedIds.size()) {
            throw ApiException.conflict("部分事件加锁后缺失，批量更新拒绝执行");
        }
        // 以锁定后的最新状态复核关闭态，避免取数与加锁之间被并发关闭
        for (Incident lockedIncident : locked) {
            if (lockedIncident.status() == IncidentStatus.CLOSED) {
                throw ApiException.conflict("事件已关闭，不能更新风险分数: "
                        + lockedIncident.incidentKey());
            }
        }

        Instant now = now();
        List<Plan> plans = new ArrayList<>();
        for (BatchScoreItem item : items) {
            Incident incident = incidentByKey.get(item.incidentKey());
            IncidentLadder current = ladder.lockByIncidentId(incident.id()).orElse(null);
            long newScore = item.score();
            int requiredLevel = levelFor(version.thresholds(), newScore);
            Integer fromLevel = null;
            Long fromScore = null;
            LadderChangeType changeType;
            IncidentLadder next;
            if (current == null) {
                changeType = LadderChangeType.INIT;
                next = new IncidentLadder(incident.id(), newScore, requiredLevel, version.id(),
                        now, operator, now, now, operator);
            } else {
                fromLevel = current.currentLevel();
                fromScore = current.score();
                int finalLevel = Math.max(current.currentLevel(), requiredLevel);
                if (finalLevel > current.currentLevel()) {
                    changeType = LadderChangeType.AUTO_ESCALATE;
                    next = new IncidentLadder(incident.id(), newScore, finalLevel, version.id(),
                            now, operator, current.enteredAt(), now, operator);
                } else {
                    changeType = LadderChangeType.SCORE_UPDATE;
                    // 分数下降不自动降级：等级、冷却起点与最近升级操作人均保持
                    next = new IncidentLadder(incident.id(), newScore, current.currentLevel(),
                            version.id(), current.lastEscalatedAt(), current.lastEscalatedBy(),
                            current.enteredAt(), now, operator);
                }
            }
            plans.add(new Plan(incident, current, next, changeType, fromLevel, fromScore,
                    newScore, requiredLevel));
        }

        // 第二轮：完整后态已验证，统一落库；任何异常都会整批回滚，不留部分结果
        List<BatchScoreResultItem> results = new ArrayList<>();
        int escalated = 0;
        int unchanged = 0;
        int initialised = 0;
        long totalDelta = 0;
        for (Plan plan : plans) {
            if (plan.current() == null) {
                ladder.insert(plan.next());
                initialised++;
                totalDelta += plan.newScore();
            } else {
                ladder.update(plan.next());
                totalDelta += plan.newScore() - plan.fromScore();
                if (plan.changeType() == LadderChangeType.AUTO_ESCALATE) {
                    escalated++;
                } else {
                    unchanged++;
                }
            }
            ladder.insertHistory(new LadderHistoryEntry(0L, plan.incident().id(),
                    plan.changeType(), plan.fromLevel(), plan.next().currentLevel(),
                    plan.fromScore(), plan.newScore(), version.id(), version.versionKey(),
                    operator, plan.next().lastEscalatedAt(), commandKey, now));
            results.add(new BatchScoreResultItem(plan.incident().incidentKey(),
                    plan.fromScore(), plan.newScore(), plan.fromLevel(),
                    plan.next().currentLevel(), plan.changeType().name(),
                    version.versionKey()));
        }
        return new BatchScoreResponse(commandKey, List.copyOf(results), results.size(),
                escalated, unchanged, initialised, totalDelta, version.versionKey());
    }

    /**
     * 降级确认：事件须存在、非 CLOSED、已登记分数；锚定 expectedLevel 必须等于当前等级
     * （并发已降或跨级重试返回 409）；只能在 2~5 级逐级下降；操作人必须与最近升级操作人不同；
     * 距最近升级必须严格超过当前依据版本的冷却分钟数。任一不满足均不写入。
     */
    @Transactional
    public DemoteResponse demote(String incidentKey, String actor, DemoteRequest req) {
        String operator = requireText(actor, "X-Actor-Id");
        String key = requireText(incidentKey, "incidentKey");
        String commandKey = requireText(req.commandKey(), "commandKey");
        Integer expectedLevel = req.expectedLevel();
        if (expectedLevel == null) {
            throw ApiException.badRequest("expectedLevel 不能为空");
        }
        if (expectedLevel < 1 || expectedLevel > ThresholdVersion.LEVELS) {
            throw ApiException.badRequest("expectedLevel 必须在 1~5 之间");
        }
        String note = requireText(req.note(), "note");
        Map<String, Object> fp = new TreeMap<>();
        fp.put("operation", OP_DEMOTE);
        fp.put("actor", operator);
        fp.put("incidentKey", key);
        fp.put("expectedLevel", expectedLevel);
        fp.put("note", note);
        return runIdempotent(commandKey, OP_DEMOTE, fingerprint(fp), DemoteResponse.class, () -> {
            Incident incident = incidents.lockByKey(key)
                    .orElseThrow(() -> ApiException.notFound("事件不存在: " + key));
            if (incident.status() == IncidentStatus.CLOSED) {
                throw ApiException.conflict("事件已关闭，不能降级: " + key);
            }
            IncidentLadder state = ladder.lockByIncidentId(incident.id())
                    .orElseThrow(() -> ApiException.conflict("事件尚未登记风险分数，不能降级: " + key));
            if (state.currentLevel() != expectedLevel) {
                throw ApiException.conflict("当前等级为 " + state.currentLevel()
                        + "，与锚定等级 " + expectedLevel + " 不一致，拒绝降级");
            }
            ThresholdVersion version = versions.findById(state.thresholdVersionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "阈值版本缺失: " + state.thresholdVersionId()));
            if (state.currentLevel() <= 1) {
                throw ApiException.illegalTransition("事件已处于最低等级 1，不能继续降级: " + key);
            }
            if (operator.equals(state.lastEscalatedBy())) {
                throw ApiException.illegalTransition("降级必须由不同于最近升级操作人的指挥员确认，"
                        + "最近升级操作人: " + state.lastEscalatedBy());
            }
            Instant now = now();
            long elapsedSeconds = Duration.between(state.lastEscalatedAt(), now).getSeconds();
            long requiredSeconds = Duration.ofMinutes(version.cooldownMinutes()).getSeconds();
            // 左闭右开：仅当 now 严格晚于 冷却起点+冷却时长 才允许；边界相等不允许（微秒精度）
            Instant earliest = state.lastEscalatedAt().plusSeconds(requiredSeconds);
            if (!now.isAfter(earliest)) {
                long remaining = Duration.between(now, earliest).getSeconds();
                throw ApiException.illegalTransition("冷却时间未超过：已过 " + elapsedSeconds
                        + " 秒，要求严格超过 " + requiredSeconds + " 秒，至少还差 " + remaining
                        + " 秒（" + version.cooldownMinutes() + " 分钟）");
            }
            int newLevel = state.currentLevel() - 1;
            int updated = ladder.demoteIfLevel(incident.id(), state.currentLevel(), newLevel,
                    operator, now);
            if (updated == 0) {
                throw ApiException.conflict("事件等级已被并发操作改变，降级仅一次生效: " + key);
            }
            ladder.insertHistory(new LadderHistoryEntry(0L, incident.id(), LadderChangeType.DEMOTE,
                    state.currentLevel(), newLevel, state.score(), state.score(), version.id(),
                    version.versionKey(), operator, state.lastEscalatedAt(), commandKey, now));
            long overtime = elapsedSeconds - requiredSeconds;
            return new DemoteResponse(key, state.currentLevel(), newLevel, state.score(), operator,
                    version.versionKey(), now, elapsedSeconds, version.cooldownMinutes(),
                    requiredSeconds, overtime);
        });
    }

    /**
     * 查询事件阶梯明细（含实际等级、按当前版本计算的要求等级、差额与冷却实际/要求/剩余值）。
     * 只读，不修改任何状态；事件不存在 404，未登记分数返回 registered=false 视图。
     */
    @Transactional(readOnly = true)
    public LadderDetailView detail(String incidentKey) {
        String key = requireText(incidentKey, "incidentKey");
        Incident incident = incidents.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + key));
        IncidentLadder state = ladder.findByIncidentId(incident.id()).orElse(null);
        return toDetail(incident, state);
    }

    /**
     * 查询事件完整等级历史，按落库顺序（id 升序）稳定返回；只读不改状态。
     */
    @Transactional(readOnly = true)
    public LadderHistoryView history(String incidentKey) {
        String key = requireText(incidentKey, "incidentKey");
        Incident incident = incidents.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + key));
        List<LadderHistoryItemView> items = ladder.listHistory(incident.id()).stream()
                .map(LadderService::toHistoryItem)
                .toList();
        return new LadderHistoryView(key, items);
    }

    /**
     * 全量诊断：列出全部已登记事件，按要求等级降序、事件键升序稳定排序；
     * 统计按当前生效版本计算等级已滞后于分数的事件数。只读不改状态。
     */
    @Transactional(readOnly = true)
    public LadderDiagnosticsView diagnostics() {
        ThresholdVersion current = versions.current().orElse(null);
        List<LadderDetailView> views = new ArrayList<>();
        int aboveRequired = 0;
        for (IncidentLadder state : ladder.listAll()) {
            Incident incident = incidents.findById(state.incidentId()).orElse(null);
            if (incident == null) {
                continue;
            }
            LadderDetailView view = toDetail(incident, state);
            views.add(view);
            if (view.requiredLevel() != null && view.requiredLevel() > view.actualLevel()) {
                aboveRequired++;
            }
        }
        views.sort(Comparator.comparing(LadderDetailView::requiredLevel,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(LadderDetailView::incidentKey));
        double ratio = views.isEmpty() ? 0d : (double) aboveRequired / views.size();
        return new LadderDiagnosticsView(current == null ? null : current.versionKey(),
                views.size(), aboveRequired, ratio, List.copyOf(views));
    }

    /**
     * 查询已发布的全部阈值版本（按发布顺序）。只读。
     */
    @Transactional(readOnly = true)
    public List<ThresholdVersionView> listVersions() {
        return versions.listAll().stream().map(LadderService::toVersionView).toList();
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private LadderDetailView toDetail(Incident incident, IncidentLadder state) {
        ThresholdVersion current = versions.current().orElse(null);
        if (state == null) {
            return new LadderDetailView(incident.incidentKey(), false, null, null, null, null,
                    null, null, null, null, null, null, null, false, "风险分数缺失", null);
        }
        ThresholdVersion basis = versions.findById(state.thresholdVersionId()).orElse(current);
        int requiredLevel = current == null
                ? state.currentLevel()
                : levelFor(current.thresholds(), state.score());
        int gap = requiredLevel - state.currentLevel();
        Instant now = now();
        Duration elapsed = Duration.between(state.lastEscalatedAt(), now);
        long elapsedSeconds = Math.max(0, elapsed.getSeconds());
        int requiredMinutes = basis == null ? 0 : basis.cooldownMinutes();
        long requiredSeconds = Duration.ofMinutes(requiredMinutes).getSeconds();
        Instant earliest = state.lastEscalatedAt().plusSeconds(requiredSeconds);
        boolean cooled = now.isAfter(earliest);
        long remainingSeconds = cooled ? 0L
                : Math.max(0, Duration.between(now, earliest).getSeconds());
        boolean demotable = incident.status() != IncidentStatus.CLOSED
                && state.currentLevel() > 1 && cooled;
        String blockReason = null;
        if (incident.status() == IncidentStatus.CLOSED) {
            blockReason = "事件已关闭";
        } else if (state.currentLevel() <= 1) {
            blockReason = "已为最低等级";
        } else if (!cooled) {
            blockReason = "降级冷却未超过：还差 " + remainingSeconds + " 秒";
        }
        return new LadderDetailView(incident.incidentKey(), true, state.score(),
                state.currentLevel(), requiredLevel, gap,
                basis == null ? null : basis.versionKey(), state.lastEscalatedAt(),
                state.lastEscalatedBy(), elapsedSeconds, requiredMinutes, requiredSeconds,
                remainingSeconds, demotable, blockReason, state.updatedAt());
    }

    private static LadderHistoryItemView toHistoryItem(LadderHistoryEntry entry) {
        return new LadderHistoryItemView(entry.id(), entry.changeType().name(), entry.fromLevel(),
                entry.toLevel(), entry.fromScore(), entry.toScore(), entry.thresholdVersionKey(),
                entry.actor(), entry.cooldownStartAt(), entry.commandKey(), entry.occurredAt());
    }

    private static ThresholdVersionView toVersionView(ThresholdVersion version) {
        return new ThresholdVersionView(version.versionKey(), List.copyOf(version.thresholds()),
                version.cooldownMinutes(), version.createdBy(), version.note(), version.createdAt());
    }

    private List<Long> validateThresholds(List<Long> thresholds) {
        if (thresholds == null || thresholds.size() != ThresholdVersion.LEVELS) {
            throw ApiException.illegalTransition(
                    "thresholds 必须恰好提供 " + ThresholdVersion.LEVELS + " 个门槛");
        }
        List<Long> values = new ArrayList<>();
        long previous = -1L;
        for (Long value : thresholds) {
            if (value == null) {
                throw ApiException.illegalTransition("thresholds 含空值");
            }
            if (value < 0) {
                throw ApiException.illegalTransition("thresholds 必须非负");
            }
            if (value <= previous) {
                throw ApiException.illegalTransition("thresholds 必须严格递增，非法位置附近值: " + value);
            }
            values.add(value);
            previous = value;
        }
        return List.copyOf(values);
    }

    private static int validateCooldown(Integer cooldownMinutes) {
        if (cooldownMinutes == null) {
            throw ApiException.illegalTransition("cooldownMinutes 不能为空");
        }
        if (cooldownMinutes < ThresholdVersion.COOLDOWN_MIN
                || cooldownMinutes > ThresholdVersion.COOLDOWN_MAX) {
            throw ApiException.illegalTransition("cooldownMinutes 必须在 "
                    + ThresholdVersion.COOLDOWN_MIN + "~" + ThresholdVersion.COOLDOWN_MAX + " 之间");
        }
        return cooldownMinutes;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    /**
     * 幂等执行：同键同参重放首次完整响应快照，同键异参 409；占位与业务写入同事务，
     * 业务失败整体回滚，不占用命令键。
     */
    private <T> T runIdempotent(String commandKey, String operation, String requestHash,
                                Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, now());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash,
                         Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("commandKey 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("commandKey 正在处理中: " + record.commandKey());
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 将规范化 Map（键已排序、集合已规范化、字符串由 JSON 转义）序列化为 JSON 后取 SHA-256，
     * 不使用未转义分隔符拼接。
     */
    private String fingerprint(Map<String, Object> canonical) {
        String json;
        try {
            json = objectMapper.writeValueAsString(new TreeMap<>(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("指纹序列化失败", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    /** 批量更新单项的完整后态计划。 */
    private record Plan(Incident incident, IncidentLadder current, IncidentLadder next,
                        LadderChangeType changeType, Integer fromLevel, Long fromScore,
                        long newScore, int requiredLevel) {
    }
}
