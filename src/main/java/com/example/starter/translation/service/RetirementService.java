package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.DraftMigrationRow;
import com.example.starter.translation.domain.Rows.RetirementImpactRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRetirementRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 术语版本退役窗口与草稿原子迁移服务。
 * 创建退役单只做只读预览；激活在同一事务内重算影响集合并冻结快照、撤批仍绑定退役术语的
 * APPROVED 译文、将目标术语版本置为 RETIRED（窗口结束也不自动恢复）。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，与草稿编辑、批准、发布、术语新版本按提交顺序串行。
 */
@Service
public class RetirementService {

    private static final Comparator<ApiDtos.ImpactEntryView> IMPACT_ORDER = Comparator
            .comparing(ApiDtos.ImpactEntryView::kind)
            .thenComparing(e -> e.publishedVersion() == null ? 0 : e.publishedVersion())
            .thenComparing(e -> e.segmentId() == null ? "" : e.segmentId())
            .thenComparing(ApiDtos.ImpactEntryView::language);

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RetirementService(TranslationRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建退役单：校验窗口、替代版本 ACTIVE、无直接与间接替代环、同版本同语言窗口不重叠、
     * retirementKey 文档内唯一；生成只读影响预览（DRAFT/APPROVED/PUBLISHED），不改写任何译文内容。
     */
    @Transactional
    public ApiDtos.RetirementView createRetirement(long documentId, ApiDtos.CreateRetirementRequest request) {
        DocumentRow document = lockDocument(documentId);
        replayIfRequestLogged(request.requestId());
        String language = normalizeLanguage(request.language());
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
        long effectiveFrom = parseUtcInstant(request.effectiveFromUtc(), "effectiveFromUtc");
        long effectiveTo = parseUtcInstant(request.effectiveToUtc(), "effectiveToUtc");
        if (effectiveFrom >= effectiveTo) {
            throw ApiException.unprocessable("生效窗口必须满足 effectiveFromUtc < effectiveToUtc（左闭右开）");
        }
        if (!repository.termVersionExists(documentId, request.termVersion())) {
            throw ApiException.notFound("术语版本不存在: " + documentId + "/" + request.termVersion());
        }
        if (request.termVersion() == request.replacementVersion()) {
            throw ApiException.unprocessable("替代版本不能与被退役版本相同（直接替代环）");
        }
        String replacementStatus = repository.findTermVersionStatus(documentId, request.replacementVersion())
                .orElseThrow(() -> ApiException.unprocessable(
                        "替代术语版本不存在: " + request.replacementVersion()));
        if (!"ACTIVE".equals(replacementStatus)) {
            throw ApiException.unprocessable("替代术语版本 " + request.replacementVersion()
                    + " 不是 ACTIVE（当前状态 " + replacementStatus + "）");
        }
        assertNoReplacementCycle(documentId, request.termVersion(), request.replacementVersion(), language);
        for (TermRetirementRow existing
                : repository.listRetirements(documentId, request.termVersion(), language)) {
            if (existing.effectiveFromUtc() < effectiveTo && effectiveFrom < existing.effectiveToUtc()) {
                throw ApiException.conflict("同一术语版本 " + request.termVersion() + "/" + language
                        + " 的生效窗口与退役单 " + existing.retirementKey() + " 重叠");
            }
        }
        if (repository.findRetirementByKey(documentId, request.retirementKey()).isPresent()) {
            throw ApiException.conflict("retirementKey 已存在: " + request.retirementKey());
        }
        List<ApiDtos.ImpactEntryView> preview = computeImpact(
                documentId, request.termVersion(), language);
        String previewJson = toJson(preview);
        long retirementId;
        try {
            retirementId = repository.insertRetirement(documentId, request.retirementKey(),
                    request.termVersion(), language, request.replacementVersion(),
                    effectiveFrom, effectiveTo, previewJson);
        } catch (DuplicateKeyException e) {
            // 并发下同 requestId 请求可能先撞业务唯一约束再撞 request_log 主键：
            // 若该 requestId 已有成功提交，转为重放（WriteExecutor 会再校验 hash，异参仍 409），
            // 否则才是真正的业务冲突。
            if (repository.findRequestLog(request.requestId()).isPresent()) {
                throw new WriteResult.ReplaySignal(request.requestId(), e);
            }
            throw ApiException.conflict("retirementKey 或生效窗口与现有退役单冲突: " + request.retirementKey());
        }
        for (ApiDtos.ImpactEntryView entry : preview) {
            repository.insertImpact(toImpactRow(documentId, retirementId, entry));
        }
        return new ApiDtos.RetirementView(documentId, request.retirementKey(), request.termVersion(), language,
                request.replacementVersion(), request.effectiveFromUtc(), request.effectiveToUtc(),
                TermRetirementRow.STATUS_DRAFT, preview);
    }

    /**
     * 激活退役单：一个事务内重算影响集合并冻结快照；仍绑定退役术语的 APPROVED 译文撤批为 DRAFT，
     * 普通 DRAFT 不改文本，已发布快照保持不可变仅登记历史影响与命中术语位置；
     * 目标术语版本置为 RETIRED，生效时刻起新批准/发布不得引用，窗口结束不自动恢复。
     */
    @Transactional
    public ApiDtos.RetirementView activateRetirement(long documentId, String retirementKey,
                                                     ApiDtos.ActivateRetirementRequest request) {
        lockDocument(documentId);
        replayIfRequestLogged(request.requestId());
        TermRetirementRow retirement = findRetirementOrThrow(documentId, retirementKey);
        if (!TermRetirementRow.STATUS_DRAFT.equals(retirement.status())) {
            throw ApiException.unprocessable("退役单已激活，不能重复激活: " + retirementKey);
        }
        String replacementStatus = repository
                .findTermVersionStatus(documentId, retirement.replacementVersion())
                .orElseThrow(() -> ApiException.unprocessable(
                        "替代术语版本不存在: " + retirement.replacementVersion()));
        if (!"ACTIVE".equals(replacementStatus)) {
            throw ApiException.unprocessable("替代术语版本 " + retirement.replacementVersion()
                    + " 已不是 ACTIVE，不能激活");
        }
        assertNoReplacementCycle(documentId, retirement.termVersion(),
                retirement.replacementVersion(), retirement.language());
        List<ApiDtos.ImpactEntryView> impact = computeImpact(
                documentId, retirement.termVersion(), retirement.language());
        for (ApiDtos.ImpactEntryView entry : impact) {
            if (!RetirementImpactRow.KIND_PUBLISHED.equals(entry.kind())) {
                // 受影响的 APPROVED 撤回为 DRAFT 并清除批准；
                // DRAFT 条目可能残留因译文重提交而失效的旧批准行，一并清除，避免历史批准悬挂
                repository.deleteApproval(documentId, entry.segmentId(), entry.language());
            }
        }
        repository.updateTermVersionStatus(documentId, retirement.termVersion(), "RETIRED");
        repository.deleteImpacts(documentId, retirement.retirementId());
        for (ApiDtos.ImpactEntryView entry : impact) {
            repository.insertImpact(toImpactRow(documentId, retirement.retirementId(), entry));
        }
        repository.activateRetirement(retirement.retirementId(), clock.instant().toEpochMilli(), toJson(impact));
        return new ApiDtos.RetirementView(documentId, retirementKey, retirement.termVersion(),
                retirement.language(), retirement.replacementVersion(),
                Instant.ofEpochMilli(retirement.effectiveFromUtc()).toString(),
                Instant.ofEpochMilli(retirement.effectiveToUtc()).toString(),
                TermRetirementRow.STATUS_ACTIVE, impact);
    }

    /** 影响查询：只读，按类型、发布版本、段落、语言稳定排序；未激活返回预览，已激活返回冻结快照。 */
    @Transactional(readOnly = true)
    public ApiDtos.RetirementView getRetirement(long documentId, String retirementKey) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        TermRetirementRow retirement = findRetirementOrThrow(documentId, retirementKey);
        List<ApiDtos.ImpactEntryView> impact = repository
                .listImpacts(documentId, retirement.retirementId()).stream()
                .map(this::toImpactView)
                .sorted(IMPACT_ORDER)
                .toList();
        return new ApiDtos.RetirementView(documentId, retirementKey, retirement.termVersion(),
                retirement.language(), retirement.replacementVersion(),
                Instant.ofEpochMilli(retirement.effectiveFromUtc()).toString(),
                Instant.ofEpochMilli(retirement.effectiveToUtc()).toString(),
                retirement.status(), impact);
    }

    /**
     * 草稿原子迁移：entries 必须恰好覆盖冻结预览中仍受影响（仍绑定退役术语且未被有效批准）的全部草稿，
     * 遗漏、多余或任一替换结果违反 replacementVersion 规则均整体 422 回滚；
     * 成功时逐稿增版、改绑替代版本，保存旧/新文本摘要与规则版本，文档草稿版本加一。
     */
    @Transactional
    public ApiDtos.MigrateDraftsResponse migrateDrafts(long documentId, String retirementKey,
                                                       ApiDtos.MigrateDraftsRequest request) {
        DocumentRow document = lockDocument(documentId);
        replayIfRequestLogged(request.requestId());
        TermRetirementRow retirement = findRetirementOrThrow(documentId, retirementKey);
        if (!TermRetirementRow.STATUS_ACTIVE.equals(retirement.status())) {
            throw ApiException.unprocessable("退役单未激活，不能迁移草稿: " + retirementKey);
        }
        if (document.draftVersion() != request.expectedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedVersion() + " 不一致");
        }
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        Set<String> impactedDraftKeys = repository.listImpacts(documentId, retirement.retirementId()).stream()
                .filter(row -> RetirementImpactRow.KIND_DRAFT.equals(row.kind())
                        || RetirementImpactRow.KIND_APPROVED.equals(row.kind()))
                .map(row -> key(row.segmentId(), row.language()))
                .collect(Collectors.toSet());
        Map<String, TranslationRow> required = new LinkedHashMap<>();
        for (TranslationRow translation : translations.values()) {
            String translationKey = key(translation.segmentId(), translation.language());
            if (translation.termVersion() == retirement.termVersion()
                    && translation.language().equals(retirement.language())
                    && impactedDraftKeys.contains(translationKey)
                    && !hasValidApproval(translation, segments, approvals)) {
                required.put(translationKey, translation);
            }
        }
        Map<String, ApiDtos.MigrationEntryInput> entries = new LinkedHashMap<>();
        for (ApiDtos.MigrationEntryInput entry : request.entries()) {
            String entryKey = key(entry.segmentId(), normalizeLanguage(entry.language()));
            if (entries.putIfAbsent(entryKey, entry) != null) {
                throw ApiException.unprocessable("迁移条目重复: " + entry.segmentId() + "/" + entry.language());
            }
        }
        Set<String> missing = new HashSet<>(required.keySet());
        missing.removeAll(entries.keySet());
        if (!missing.isEmpty()) {
            throw ApiException.unprocessable("迁移遗漏仍受影响的草稿: " + String.join(", ", missing));
        }
        Set<String> extra = new HashSet<>(entries.keySet());
        extra.removeAll(required.keySet());
        if (!extra.isEmpty()) {
            throw ApiException.unprocessable("迁移包含不在受影响草稿集合中的条目: " + String.join(", ", extra));
        }
        List<TermRuleRow> replacementRules = repository.listTermRules(
                documentId, retirement.replacementVersion());
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (ApiDtos.MigrationEntryInput entry : entries.values()) {
            String language = normalizeLanguage(entry.language());
            SegmentRow segment = segments.get(entry.segmentId());
            violations.addAll(findViolations(segment.sourceText(), language, entry.content(), replacementRules));
        }
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("替换结果违反 " + violations.size() + " 条替代版本术语规则", violations);
        }
        long nowUtc = clock.instant().toEpochMilli();
        List<ApiDtos.MigrationResultView> results = new ArrayList<>();
        List<Map.Entry<String, ApiDtos.MigrationEntryInput>> ordered = entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (Map.Entry<String, ApiDtos.MigrationEntryInput> item : ordered) {
            ApiDtos.MigrationEntryInput entry = item.getValue();
            String language = normalizeLanguage(entry.language());
            TranslationRow current = required.get(item.getKey());
            SegmentRow segment = segments.get(entry.segmentId());
            int translationVersion = current.translationVersion() + 1;
            repository.upsertTranslation(documentId, new TranslationRow(entry.segmentId(), language,
                    entry.content(), current.author(), segment.sourceVersion(), translationVersion,
                    retirement.replacementVersion()));
            String oldHash = sha256(current.content());
            String newHash = sha256(entry.content());
            repository.insertMigration(new DraftMigrationRow(documentId, retirement.retirementId(),
                    request.expectedVersion(), retirement.replacementVersion(),
                    oldHash.substring(0, 32), oldHash, newHash.substring(0, 32), newHash,
                    entry.segmentId(), language, nowUtc, request.requestId()));
            results.add(new ApiDtos.MigrationResultView(entry.segmentId(), language, translationVersion,
                    oldHash.substring(0, 32), newHash.substring(0, 32), retirement.replacementVersion()));
        }
        int draftVersion = document.draftVersion() + 1;
        repository.updateDraftVersion(documentId, draftVersion);
        return new ApiDtos.MigrateDraftsResponse(documentId, retirementKey, results.size(),
                draftVersion, results);
    }

    /**
     * 计算影响集合（预览与激活共用）：绑定退役术语版本的译文按批准有效性分为 DRAFT/APPROVED，
     * 绑定该版本的发布快照登记为 PUBLISHED；均记录实际命中的术语位置，稳定排序。
     */
    private List<ApiDtos.ImpactEntryView> computeImpact(long documentId, int termVersion, String language) {
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<TermRuleRow> rules = repository.listTermRules(documentId, termVersion).stream()
                .filter(rule -> rule.language().equals(language))
                .toList();
        List<ApiDtos.ImpactEntryView> impact = new ArrayList<>();
        for (TranslationRow translation : repository.listTranslations(documentId)) {
            if (translation.termVersion() != termVersion || !translation.language().equals(language)) {
                continue;
            }
            SegmentRow segment = segments.get(translation.segmentId());
            String kind = hasValidApproval(translation, segments, approvals)
                    ? RetirementImpactRow.KIND_APPROVED : RetirementImpactRow.KIND_DRAFT;
            impact.add(new ApiDtos.ImpactEntryView(kind, null, translation.segmentId(), language,
                    computeHits(segment.sourceText(), rules)));
        }
        for (int publishedVersion : repository.listPublishedVersionsByTermVersion(documentId, termVersion)) {
            String snapshotJson = repository.findSnapshot(documentId, publishedVersion)
                    .orElseThrow(() -> new IllegalStateException(
                            "发布快照缺失: " + documentId + "/" + publishedVersion));
            boolean anyHit = false;
            try {
                JsonNode snapshot = objectMapper.readTree(snapshotJson);
                for (JsonNode segmentNode : snapshot.path("segments")) {
                    String segmentId = segmentNode.path("segmentId").asText();
                    List<ApiDtos.HitTermView> hits = computeHits(
                            segmentNode.path("sourceText").asText(), rules);
                    if (!hits.isEmpty()) {
                        anyHit = true;
                        impact.add(new ApiDtos.ImpactEntryView(RetirementImpactRow.KIND_PUBLISHED,
                                publishedVersion, segmentId, language, hits));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("发布快照解析失败: " + documentId + "/" + publishedVersion, e);
            }
            if (!anyHit) {
                impact.add(new ApiDtos.ImpactEntryView(RetirementImpactRow.KIND_PUBLISHED,
                        publishedVersion, null, language, List.of()));
            }
        }
        impact.sort(IMPACT_ORDER);
        return impact;
    }

    /** 替代环检测：沿同语言退役链从替代版本出发，若回到被退役版本则构成直接或间接替代环。 */
    private void assertNoReplacementCycle(long documentId, int termVersion, int replacementVersion,
                                          String language) {
        Map<Integer, Integer> chain = new HashMap<>();
        for (TermRetirementRow row : repository.listAllRetirements(documentId)) {
            if (row.language().equals(language)) {
                chain.putIfAbsent(row.termVersion(), row.replacementVersion());
            }
        }
        Set<Integer> visited = new HashSet<>();
        int current = replacementVersion;
        while (chain.containsKey(current)) {
            if (!visited.add(current)) {
                break;
            }
            current = chain.get(current);
            if (current == termVersion) {
                throw ApiException.unprocessable("替代版本 " + replacementVersion
                        + " 与术语版本 " + termVersion + " 形成直接或间接替代环");
            }
        }
    }

    private boolean hasValidApproval(TranslationRow translation, Map<String, SegmentRow> segments,
                                     Map<String, ApprovalRow> approvals) {
        ApprovalRow approval = approvals.get(key(translation.segmentId(), translation.language()));
        SegmentRow segment = segments.get(translation.segmentId());
        return approval != null && segment != null
                && approval.translationVersion() == translation.translationVersion()
                && approval.sourceVersion() == segment.sourceVersion();
    }

    /** 术语命中位置：源文按 Unicode 原文、区分大小写连续子串匹配，记录全部出现位置（字符偏移，左闭右开）。 */
    private static List<ApiDtos.HitTermView> computeHits(String sourceText, List<TermRuleRow> rules) {
        List<ApiDtos.HitTermView> hits = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            int from = 0;
            while (true) {
                int index = sourceText.indexOf(rule.sourceTerm(), from);
                if (index < 0) {
                    break;
                }
                hits.add(new ApiDtos.HitTermView(rule.sourceTerm(), index,
                        index + rule.sourceTerm().length()));
                from = index + 1;
            }
        }
        return hits;
    }

    private static List<ApiDtos.TermRuleView> findViolations(String sourceText, String language, String content,
                                                             List<TermRuleRow> rules) {
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            if (rule.language().equals(language) && sourceText.contains(rule.sourceTerm())
                    && !content.contains(rule.requiredTranslation())) {
                violations.add(new ApiDtos.TermRuleView(rule.sourceTerm(), rule.language(),
                        rule.requiredTranslation()));
            }
        }
        return violations;
    }

    private RetirementImpactRow toImpactRow(long documentId, long retirementId,
                                            ApiDtos.ImpactEntryView entry) {
        return new RetirementImpactRow(documentId, retirementId, entry.publishedVersion(),
                entry.segmentId(), entry.language(), entry.kind(), toJson(entry.hitTerms()));
    }

    private ApiDtos.ImpactEntryView toImpactView(RetirementImpactRow row) {
        List<ApiDtos.HitTermView> hits;
        try {
            hits = objectMapper.readValue(row.hitTermsJson(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("影响条目命中术语解析失败", e);
        }
        return new ApiDtos.ImpactEntryView(row.kind(), row.publishedVersion(), row.segmentId(),
                row.language(), hits);
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    /**
     * 拿到文档行锁后复查幂等记录：同 requestId 并发事务在锁处串行，
     * 先提交者的成功结果此时对后到者可见；命中则回滚当前事务并由 WriteExecutor 重放。
     */
    private void replayIfRequestLogged(String requestId) {
        if (repository.findRequestLog(requestId).isPresent()) {
            throw new WriteResult.ReplaySignal(requestId, null);
        }
    }

    private TermRetirementRow findRetirementOrThrow(long documentId, String retirementKey) {
        return repository.findRetirementByKey(documentId, retirementKey)
                .orElseThrow(() -> ApiException.notFound("退役单不存在: " + documentId + "/" + retirementKey));
    }

    private static long parseUtcInstant(String value, String field) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw ApiException.unprocessable(field + " 不是合法的 UTC 时刻（ISO-8601）: " + value);
        }
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}
