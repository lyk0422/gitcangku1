package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.RetirementRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 术语版本退役单服务。
 * 创建时仅预览影响（不改写内容）；激活在同一事务内重算影响集合并冻结快照：
 * 历史发布快照不可变、仅落受影响标记与命中位置，仍绑定退役术语的 APPROVED 译文撤回为 DRAFT 并清除批准，
 * 普通 DRAFT 不自动改文本。草稿迁移要求恰好覆盖仍受影响的全部草稿并通过替代版本规则校验，
 * 任一遗漏、多余或违规整体回滚。所有写操作先对文档行加 FOR UPDATE 行锁，与其他写操作按提交顺序串行。
 */
@Service
public class RetirementService {

    /** 退役单状态：已创建未激活。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 退役单状态：已激活并冻结影响快照。 */
    public static final String STATUS_ACTIVATED = "ACTIVATED";

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RetirementService(TranslationRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建退役单：校验窗口左闭右开、被退役版本存在、替代版本 ACTIVE 且不形成直接或间接替代环、
     * 同一术语版本生效窗口不重叠；成功后仅返回影响预览，不改写任何译文内容。
     */
    @Transactional
    public ApiDtos.RetirementResponse createRetirement(long documentId,
                                                       ApiDtos.CreateRetirementRequest request) {
        DocumentRow document = lockDocument(documentId);
        Instant from = request.effectiveFrom();
        Instant to = request.effectiveTo();
        if (!from.isBefore(to)) {
            throw ApiException.unprocessable("生效窗口起点必须早于终点（左闭右开）: "
                    + from + " ~ " + to);
        }
        if (!repository.termVersionExists(documentId, request.termVersion())) {
            throw ApiException.notFound("术语版本不存在: " + documentId + "/" + request.termVersion());
        }
        if (request.termVersion() == request.replacementVersion()) {
            throw ApiException.unprocessable("替代版本不能与被退役版本相同: " + request.termVersion());
        }
        if (!repository.termVersionExists(documentId, request.replacementVersion())) {
            throw ApiException.unprocessable("替代版本不存在，不是 ACTIVE: " + request.replacementVersion());
        }
        List<RetirementRow> existing = repository.listRetirements(documentId);
        Instant now = clock.instant();
        if (retiredTermVersions(existing, now).contains(request.replacementVersion())) {
            throw ApiException.unprocessable("替代版本已退役，不是 ACTIVE: " + request.replacementVersion());
        }
        for (RetirementRow row : existing) {
            if (row.termVersion() == request.termVersion()
                    && Instant.parse(row.effectiveFrom()).isBefore(to)
                    && from.isBefore(Instant.parse(row.effectiveTo()))) {
                throw ApiException.unprocessable("同一术语版本 " + request.termVersion()
                        + " 的生效窗口与退役单 " + row.retirementKey() + " 重叠");
            }
        }
        if (formsReplacementCycle(existing, request.termVersion(), request.replacementVersion())) {
            throw ApiException.unprocessable("替代关系形成环: " + request.termVersion()
                    + " -> " + request.replacementVersion());
        }
        RetirementRow row = new RetirementRow(request.retirementKey(), documentId, request.termVersion(),
                request.replacementVersion(), from.toString(), to.toString(), STATUS_PENDING, null);
        try {
            repository.insertRetirement(row);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("retirementKey 已存在: " + request.retirementKey());
        }
        return toResponse(row, computeImpact(document, row.termVersion()));
    }

    /**
     * 激活退役单：一个事务内重算影响集合并冻结快照；历史发布快照保持不可变、仅落受影响标记与命中位置；
     * 仍绑定退役术语的 APPROVED 译文撤回为 DRAFT 并清除批准；普通 DRAFT 不自动改文本；草稿版本加一。
     */
    @Transactional
    public ApiDtos.RetirementResponse activateRetirement(long documentId, String retirementKey) {
        findRetirementOrThrow(documentId, retirementKey);
        DocumentRow document = lockDocument(documentId);
        RetirementRow row = findRetirementOrThrow(documentId, retirementKey);
        if (!STATUS_PENDING.equals(row.status())) {
            throw ApiException.conflict("退役单已激活: " + retirementKey);
        }
        ApiDtos.RetirementImpact impact = computeImpact(document, row.termVersion());
        repository.activateRetirement(retirementKey, toJson(impact));
        for (ApiDtos.PublishedImpactEntry entry : impact.published()) {
            repository.insertSnapshotImpact(retirementKey, documentId, entry.publishedVersion(),
                    entry.segmentId(), entry.language(), toJson(entry.hits()));
        }
        for (ApiDtos.ImpactEntry entry : impact.approved()) {
            repository.deleteApproval(documentId, entry.segmentId(), entry.language());
        }
        bumpDraftVersion(document);
        RetirementRow activated = new RetirementRow(row.retirementKey(), row.documentId(), row.termVersion(),
                row.replacementVersion(), row.effectiveFrom(), row.effectiveTo(), STATUS_ACTIVATED,
                row.impactJson());
        return toResponse(activated, impact);
    }

    /**
     * 影响查询：只读、稳定排序；未激活返回实时预览，已激活返回冻结快照。
     */
    @Transactional(readOnly = true)
    public ApiDtos.RetirementResponse getImpact(long documentId, String retirementKey) {
        RetirementRow row = findRetirementOrThrow(documentId, retirementKey);
        if (STATUS_ACTIVATED.equals(row.status())) {
            return toResponse(row, fromJson(row.impactJson()));
        }
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return toResponse(row, computeImpact(document, row.termVersion()));
    }

    /**
     * 草稿迁移：expectedVersion 必须等于当前草稿版本（不符 409）；提交的草稿集合必须恰好覆盖
     * 仍受影响的全部草稿，且每段替换后正文通过替代版本规则校验；遗漏、多余或任一违规整体 422 回滚。
     * 成功逐稿递增译文版本、绑定替代术语版本，保存旧/新文本摘要及规则版本，草稿版本加一。
     */
    @Transactional
    public ApiDtos.MigrateDraftsResponse migrateDrafts(long documentId, String retirementKey, String actorId,
                                                       ApiDtos.MigrateDraftsRequest request) {
        findRetirementOrThrow(documentId, retirementKey);
        DocumentRow document = lockDocument(documentId);
        RetirementRow row = findRetirementOrThrow(documentId, retirementKey);
        if (!STATUS_ACTIVATED.equals(row.status())) {
            throw ApiException.conflict("退役单未激活，不能迁移草稿: " + retirementKey);
        }
        if (document.draftVersion() != request.expectedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedVersion() + " 不一致");
        }
        ApiDtos.RetirementImpact impact = computeImpact(document, row.termVersion());
        Map<String, ApiDtos.ImpactEntry> affected = impact.drafts().stream()
                .collect(Collectors.toMap(e -> key(e.segmentId(), e.language()), Function.identity()));
        Map<String, ApiDtos.DraftReplacement> replacements = new LinkedHashMap<>();
        for (ApiDtos.DraftReplacement draft : request.drafts()) {
            String language = normalizeLanguage(draft.language());
            String key = key(draft.segmentId(), language);
            if (replacements.put(key, new ApiDtos.DraftReplacement(draft.segmentId(), language,
                    draft.content())) != null) {
                throw ApiException.unprocessable("草稿替换重复: " + draft.segmentId() + "/" + language);
            }
            if (!affected.containsKey(key)) {
                throw ApiException.unprocessable(
                        "草稿不在仍受影响集合中（多余）: " + draft.segmentId() + "/" + language);
            }
        }
        if (replacements.size() != affected.size()) {
            Set<String> missing = new TreeSet<>(affected.keySet());
            missing.removeAll(replacements.keySet());
            throw ApiException.unprocessable("草稿迁移遗漏 " + missing.size() + " 条: " + missing);
        }
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        List<TermRuleRow> replacementRules = repository.listTermRules(documentId, row.replacementVersion());
        List<ApiDtos.TermRuleView> violations = new ArrayList<>();
        for (ApiDtos.DraftReplacement draft : replacements.values()) {
            SegmentRow segment = segments.get(draft.segmentId());
            violations.addAll(TranslationService.findViolations(segment.sourceText(), draft.language(),
                    draft.content(), replacementRules));
        }
        if (!violations.isEmpty()) {
            throw ApiException.termViolation("迁移后译文违反 " + violations.size() + " 条替代版本术语规则", violations);
        }
        List<ApiDtos.DraftReplacement> sorted = replacements.values().stream()
                .sorted((a, b) -> key(a.segmentId(), a.language())
                        .compareTo(key(b.segmentId(), b.language())))
                .toList();
        List<ApiDtos.MigrationEntry> entries = new ArrayList<>();
        for (ApiDtos.DraftReplacement draft : sorted) {
            TranslationRow translation = repository
                    .findTranslation(documentId, draft.segmentId(), draft.language())
                    .orElseThrow(() -> ApiException.notFound(
                            "译文不存在: " + draft.segmentId() + "/" + draft.language()));
            int translationVersion = translation.translationVersion() + 1;
            repository.upsertTranslation(documentId, new TranslationRow(draft.segmentId(), draft.language(),
                    draft.content(), actorId, translation.sourceVersion(), translationVersion,
                    row.replacementVersion()));
            String oldDigest = sha256(translation.content());
            String newDigest = sha256(draft.content());
            repository.insertMigration(retirementKey, documentId, draft.segmentId(), draft.language(),
                    oldDigest, newDigest, row.replacementVersion());
            entries.add(new ApiDtos.MigrationEntry(draft.segmentId(), draft.language(), translationVersion,
                    oldDigest, newDigest, row.replacementVersion()));
        }
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.MigrateDraftsResponse(retirementKey, documentId, entries.size(), draftVersion, entries);
    }

    /**
     * 计算指定时刻已退役的术语版本集合：存在已激活退役单且生效窗口起点不晚于该时刻；
     * 窗口结束后不自动恢复。
     */
    public static Set<Integer> retiredTermVersions(List<RetirementRow> retirements, Instant now) {
        Set<Integer> retired = new HashSet<>();
        for (RetirementRow row : retirements) {
            if (STATUS_ACTIVATED.equals(row.status()) && !Instant.parse(row.effectiveFrom()).isAfter(now)) {
                retired.add(row.termVersion());
            }
        }
        return retired;
    }

    /**
     * 计算退役影响集合：绑定被退役术语版本且源文实际命中该版本规则的译文，
     * 按当前批准状态分为 DRAFT/APPROVED；历史发布快照逐条解析标记，全部稳定排序。
     */
    private ApiDtos.RetirementImpact computeImpact(DocumentRow document, int retiredVersion) {
        long documentId = document.documentId();
        List<TermRuleRow> rules = repository.listTermRules(documentId, retiredVersion);
        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        List<ApiDtos.ImpactEntry> drafts = new ArrayList<>();
        List<ApiDtos.ImpactEntry> approved = new ArrayList<>();
        for (TranslationRow translation : repository.listTranslations(documentId)) {
            if (translation.termVersion() != retiredVersion) {
                continue;
            }
            SegmentRow segment = segments.get(translation.segmentId());
            List<ApiDtos.TermHit> hits = findHits(segment.sourceText(), translation.language(), rules);
            if (hits.isEmpty()) {
                continue;
            }
            ApprovalRow approval = approvals.get(key(translation.segmentId(), translation.language()));
            boolean validApproval = approval != null
                    && approval.sourceVersion() == segment.sourceVersion()
                    && approval.translationVersion() == translation.translationVersion();
            ApiDtos.ImpactEntry entry = new ApiDtos.ImpactEntry(translation.segmentId(), translation.language(),
                    translation.translationVersion(), validApproval ? "APPROVED" : "DRAFT", hits);
            (validApproval ? approved : drafts).add(entry);
        }
        List<ApiDtos.PublishedImpactEntry> published = new ArrayList<>();
        for (Map.Entry<Integer, String> snapshot : repository.listSnapshotJsons(documentId)) {
            published.addAll(publishedImpacts(snapshot.getKey(), snapshot.getValue(), retiredVersion, rules));
        }
        return new ApiDtos.RetirementImpact(List.copyOf(drafts), List.copyOf(approved), List.copyOf(published));
    }

    /** 解析单个发布快照，找出绑定被退役术语版本且实际命中规则的译文位置。 */
    private List<ApiDtos.PublishedImpactEntry> publishedImpacts(int publishedVersion, String snapshotJson,
                                                                int retiredVersion, List<TermRuleRow> rules) {
        List<ApiDtos.PublishedImpactEntry> impacts = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(snapshotJson);
            for (com.fasterxml.jackson.databind.JsonNode segmentNode : root.get("segments")) {
                String segmentId = segmentNode.get("segmentId").asText();
                String sourceText = segmentNode.get("sourceText").asText();
                for (com.fasterxml.jackson.databind.JsonNode translationNode : segmentNode.get("translations")) {
                    if (translationNode.get("termVersion").asInt() != retiredVersion) {
                        continue;
                    }
                    String language = translationNode.get("language").asText();
                    List<ApiDtos.TermHit> hits = findHits(sourceText, language, rules);
                    if (!hits.isEmpty()) {
                        impacts.add(new ApiDtos.PublishedImpactEntry(publishedVersion, segmentId, language,
                                hits));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("发布快照解析失败: " + publishedVersion, e);
        }
        return impacts;
    }

    /** 术语命中位置：源文按 Unicode 原文、区分大小写连续子串匹配，记录首次命中偏移。 */
    private static List<ApiDtos.TermHit> findHits(String sourceText, String language, List<TermRuleRow> rules) {
        List<ApiDtos.TermHit> hits = new ArrayList<>();
        for (TermRuleRow rule : rules) {
            if (rule.language().equals(language)) {
                int index = sourceText.indexOf(rule.sourceTerm());
                if (index >= 0) {
                    hits.add(new ApiDtos.TermHit(rule.sourceTerm(), index));
                }
            }
        }
        return hits;
    }

    /** 替代环检测：既有替代边加上新边 termVersion→replacementVersion 后，replacementVersion 可达 termVersion 即成环。 */
    private static boolean formsReplacementCycle(List<RetirementRow> existing, int termVersion,
                                                 int replacementVersion) {
        Map<Integer, List<Integer>> edges = new HashMap<>();
        for (RetirementRow row : existing) {
            edges.computeIfAbsent(row.termVersion(), k -> new ArrayList<>()).add(row.replacementVersion());
        }
        edges.computeIfAbsent(termVersion, k -> new ArrayList<>()).add(replacementVersion);
        Set<Integer> visited = new HashSet<>();
        List<Integer> stack = new ArrayList<>();
        stack.add(replacementVersion);
        while (!stack.isEmpty()) {
            int current = stack.remove(stack.size() - 1);
            if (current == termVersion) {
                return true;
            }
            if (visited.add(current)) {
                stack.addAll(edges.getOrDefault(current, List.of()));
            }
        }
        return false;
    }

    private RetirementRow findRetirementOrThrow(long documentId, String retirementKey) {
        RetirementRow row = repository.findRetirement(retirementKey)
                .orElseThrow(() -> ApiException.notFound("退役单不存在: " + retirementKey));
        if (row.documentId() != documentId) {
            throw ApiException.notFound("退役单不存在: " + documentId + "/" + retirementKey);
        }
        return row;
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private int bumpDraftVersion(DocumentRow document) {
        int draftVersion = document.draftVersion() + 1;
        repository.updateDraftVersion(document.documentId(), draftVersion);
        return draftVersion;
    }

    private ApiDtos.RetirementResponse toResponse(RetirementRow row, ApiDtos.RetirementImpact impact) {
        return new ApiDtos.RetirementResponse(row.retirementKey(), row.documentId(), row.termVersion(),
                row.replacementVersion(), row.status(), Instant.parse(row.effectiveFrom()),
                Instant.parse(row.effectiveTo()), impact);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("影响快照序列化失败", e);
        }
    }

    private ApiDtos.RetirementImpact fromJson(String impactJson) {
        try {
            return objectMapper.readValue(impactJson, ApiDtos.RetirementImpact.class);
        } catch (Exception e) {
            throw new IllegalStateException("影响快照反序列化失败", e);
        }
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 文本摘要：UTF-8 字节的 SHA-256 十六进制。 */
    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
