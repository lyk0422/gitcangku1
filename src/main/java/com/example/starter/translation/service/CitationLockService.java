package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiDtos.AnchorEventView;
import com.example.starter.translation.api.ApiDtos.AnchorHistoryResponse;
import com.example.starter.translation.api.ApiDtos.AnchorInput;
import com.example.starter.translation.api.ApiDtos.AnchorListResponse;
import com.example.starter.translation.api.ApiDtos.AnchorMappingInput;
import com.example.starter.translation.api.ApiDtos.AnchorView;
import com.example.starter.translation.api.ApiDtos.IssueView;
import com.example.starter.translation.api.ApiDtos.RegisterAnchorsRequest;
import com.example.starter.translation.api.ApiDtos.RegisterAnchorsResponse;
import com.example.starter.translation.api.ApiDtos.ReleaseAnchorRequest;
import com.example.starter.translation.api.ApiDtos.ReleaseAnchorResponse;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.CitationAnchorEventRow;
import com.example.starter.translation.domain.Rows.CitationAnchorRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 法定引文锚点的登记、解除、迁移校验与查询。
 * 区间一律按 Java 字符偏移、左闭右开解释；所有时间使用 UTC，输出固定微秒精度。
 * 锚点仅可登记在当前已批准（批准与现行源文/译文版本一致）的译文段落上。
 */
@Service
public class CitationLockService {

    /** 事件类型：登记 / 区间迁移 / 解除。 */
    public static final String EVENT_REGISTERED = "REGISTERED";
    public static final String EVENT_MIGRATED = "MIGRATED";
    public static final String EVENT_RELEASED = "RELEASED";

    private static final DateTimeFormatter UTC_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final TranslationRepository repository;
    private final Clock clock;

    public CitationLockService(TranslationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 锚点登记：段落译文必须存在且批准与当前源文/译文版本一致；
     * 区间必须落在当前译文内（左闭右开）、互不交叉；同一段落同一引用标识只能登记一次（解除后也不可复用）。
     * 任一锚点不满足则整体 422 回滚；成功后锚点行与 REGISTERED 事件原子落库，草稿版本加一。
     */
    @Transactional
    public RegisterAnchorsResponse registerAnchors(long documentId, String segmentId, String language,
                                                   String actorId, RegisterAnchorsRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        List<IssueView> issues = new ArrayList<>();
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            issues.add(issue("LANGUAGE_NOT_SUPPORTED", segmentId, normalizedLanguage, null,
                    "语言不在文档目标语言中: " + normalizedLanguage));
        }
        SegmentRow segment = repository.findSegment(documentId, segmentId).orElse(null);
        if (segment == null) {
            issues.add(issue("SEGMENT_NOT_FOUND", segmentId, normalizedLanguage, null,
                    "段落不存在: " + segmentId));
        }
        TranslationRow translation = segment == null ? null
                : repository.findTranslation(documentId, segmentId, normalizedLanguage).orElse(null);
        if (segment != null && translation == null) {
            issues.add(issue("NO_TRANSLATION", segmentId, normalizedLanguage, null,
                    "译文不存在，无法登记锚点: " + segmentId + "/" + normalizedLanguage));
        }
        ApprovalRow approval = translation == null ? null
                : repository.findApproval(documentId, segmentId, normalizedLanguage).orElse(null);
        if (translation != null && approval == null) {
            issues.add(issue("NO_APPROVED_TRANSLATION", segmentId, normalizedLanguage, null,
                    "译文尚未批准，不能登记法定引文锚点"));
        }
        if (translation != null && approval != null
                && (approval.translationVersion() != translation.translationVersion()
                || approval.sourceVersion() != segment.sourceVersion())) {
            issues.add(issue("APPROVAL_NOT_CURRENT", segmentId, normalizedLanguage, null,
                    "批准已失效：批准对应译文版本 " + approval.translationVersion() + "、源文版本 "
                            + approval.sourceVersion() + "，当前译文版本 " + translation.translationVersion()
                            + "、源文版本 " + segment.sourceVersion()));
        }

        List<CitationAnchorRow> existing = (translation == null) ? List.of()
                : repository.listAnchors(documentId, segmentId, normalizedLanguage);
        Set<String> existingKeys = new HashSet<>();
        List<int[]> lockedRanges = new ArrayList<>();
        for (CitationAnchorRow row : existing) {
            existingKeys.add(row.citationKey());
            if (Rows.ANCHOR_LOCKED.equals(row.status())) {
                lockedRanges.add(new int[]{row.rangeStart(), row.rangeEnd()});
            }
        }

        List<AnchorInput> inputs = request.anchors();
        Set<String> requestKeys = new HashSet<>();
        List<int[]> requestedRanges = new ArrayList<>();
        for (AnchorInput input : inputs) {
            String normalizedKey = input.citationKey().trim();
            if (!requestKeys.add(normalizedKey)) {
                issues.add(issue("ANCHOR_KEY_DUPLICATE_REQUEST", segmentId, normalizedLanguage, null,
                        "同一请求内引用标识重复: " + normalizedKey));
            }
            if (existingKeys.contains(normalizedKey)) {
                issues.add(issue("ANCHOR_KEY_ALREADY_EXISTS", segmentId, normalizedLanguage, null,
                        "引用标识在该段落已登记（含已解除），同一段落只能锁定一次: " + normalizedKey));
            }
            int contentLength = translation == null ? 0 : translation.content().length();
            if (input.rangeStart() >= input.rangeEnd()) {
                issues.add(issue("ANCHOR_RANGE_INVALID", segmentId, normalizedLanguage, null,
                        "区间非法（要求 start < end）：实际 start=" + input.rangeStart()
                                + "，end=" + input.rangeEnd()));
            }
            if (input.rangeStart() < 0 || input.rangeEnd() > contentLength || input.rangeStart() >= input.rangeEnd()) {
                issues.add(issue("ANCHOR_OUT_OF_BOUNDS", segmentId, normalizedLanguage, null,
                        "区间 [" + input.rangeStart() + "," + input.rangeEnd() + ") 超出当前已批准译文"
                                + "（实际字符长度 " + contentLength + "，要求 0 <= start < end <= "
                                + contentLength + "，终点超出 " + Math.max(0, input.rangeEnd() - contentLength)
                                + " 个字符）"));
            } else {
                requestedRanges.add(new int[]{input.rangeStart(), input.rangeEnd()});
            }
        }
        for (int i = 0; i < requestedRanges.size(); i++) {
            for (int j = i + 1; j < requestedRanges.size(); j++) {
                int[] a = requestedRanges.get(i);
                int[] b = requestedRanges.get(j);
                if (a[0] < b[1] && b[0] < a[1]) {
                    issues.add(issue("ANCHOR_RANGE_OVERLAP", segmentId, normalizedLanguage, null,
                            "登记区间交叉: [" + a[0] + "," + a[1] + ") 与 [" + b[0] + "," + b[1] + ")"));
                }
            }
            for (int[] locked : lockedRanges) {
                int[] a = requestedRanges.get(i);
                if (a[0] < locked[1] && locked[0] < a[1]) {
                    issues.add(issue("ANCHOR_RANGE_OVERLAP", segmentId, normalizedLanguage, null,
                            "登记区间与已锁定锚点区间交叉: [" + a[0] + "," + a[1] + ") 与已有区间 ["
                                    + locked[0] + "," + locked[1] + ")"));
                }
            }
        }
        if (!issues.isEmpty()) {
            throw ApiException.validationIssues("锚点登记校验失败，共 " + issues.size() + " 项问题", issues);
        }

        Instant now = clock.instant();
        List<AnchorView> registered = new ArrayList<>();
        for (AnchorInput input : inputs) {
            String normalizedKey = input.citationKey().trim();
            String anchorText = translation.content().substring(input.rangeStart(), input.rangeEnd());
            CitationAnchorRow row = new CitationAnchorRow(0L, documentId, segmentId, normalizedLanguage,
                    normalizedKey, input.rangeStart(), input.rangeEnd(), anchorText, input.lockReason(),
                    actorId, translation.translationVersion(), segment.sourceVersion(),
                    Rows.ANCHOR_LOCKED, null, null, now, null);
            long anchorId;
            try {
                anchorId = repository.insertAnchor(row);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw ApiException.conflict("引用标识在该段落已锁定（并发登记冲突）: " + normalizedKey);
            }
            CitationAnchorRow inserted = new CitationAnchorRow(anchorId, documentId, segmentId, normalizedLanguage,
                    normalizedKey, input.rangeStart(), input.rangeEnd(), anchorText, input.lockReason(),
                    actorId, translation.translationVersion(), segment.sourceVersion(),
                    Rows.ANCHOR_LOCKED, null, null, now, null);
            repository.insertAnchorEvent(new CitationAnchorEventRow(0L, documentId, anchorId, segmentId,
                    normalizedLanguage, normalizedKey, EVENT_REGISTERED, input.rangeStart(), input.rangeEnd(),
                    null, null, anchorText, input.lockReason(), actorId,
                    translation.translationVersion(), segment.sourceVersion(), now));
            registered.add(toView(inserted));
        }
        bumpDraftVersion(document);
        return new RegisterAnchorsResponse(documentId, registered.size(), registered);
    }

    /**
     * 解除锚点：解除人必须是法务角色且不同于登记人，理由不可变；
     * 已解除再次解除返回 422。锚点行与 RELEASED 事件保留，已发布快照中的锚点仍可追溯；草稿版本加一。
     */
    @Transactional
    public ReleaseAnchorResponse releaseAnchor(long documentId, long anchorId, String actorId, String rolesHeader,
                                               ReleaseAnchorRequest request) {
        DocumentRow document = lockDocument(documentId);
        CitationAnchorRow anchor = repository.findAnchor(anchorId)
                .orElseThrow(() -> ApiException.notFound("锚点不存在: " + anchorId));
        if (anchor.documentId() != documentId) {
            throw ApiException.notFound("锚点不属于文档 " + documentId + ": " + anchorId);
        }
        if (!hasLegalRole(rolesHeader)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "LEGAL_ROLE_REQUIRED",
                    "解除法定引文锚点须由法务角色确认，X-Actor-Roles 实际为 '" + rolesHeader + "'，要求包含 legal");
        }
        if (anchor.createdBy().equals(actorId)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "SELF_CONFIRMATION_FORBIDDEN",
                    "解除人不得是锚点登记人：登记人与确认人均为 '" + actorId + "'");
        }
        if (Rows.ANCHOR_RELEASED.equals(anchor.status())) {
            throw ApiException.unprocessable("锚点已解除，不能重复解除: " + anchorId
                    + "（解除人 " + anchor.releasedBy() + "）");
        }
        Instant now = clock.instant();
        int updated = repository.releaseAnchor(anchorId, actorId, request.reason(), now);
        if (updated == 0) {
            throw ApiException.conflict("锚点状态已变化，解除失败: " + anchorId);
        }
        repository.insertAnchorEvent(new CitationAnchorEventRow(0L, documentId, anchorId, anchor.segmentId(),
                anchor.language(), anchor.citationKey(), EVENT_RELEASED, anchor.rangeStart(), anchor.rangeEnd(),
                null, null, anchor.anchorText(), request.reason(), actorId,
                anchor.translationVersion(), anchor.sourceVersion(), now));
        bumpDraftVersion(document);
        return new ReleaseAnchorResponse(documentId, anchorId, Rows.ANCHOR_RELEASED, actorId, formatUtc(now));
    }

    /** 明细查询：某段落某语言全部锚点（含已解除），按 anchorId 排序；读取不改变状态。 */
    @Transactional(readOnly = true)
    public AnchorListResponse listAnchors(long documentId, String segmentId, String language) {
        String normalizedLanguage = normalizeLanguage(language);
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        List<AnchorView> views = repository.listAnchors(documentId, segmentId, normalizedLanguage).stream()
                .map(CitationLockService::toView).toList();
        return new AnchorListResponse(documentId, segmentId, normalizedLanguage, views.size(), views);
    }

    /** 历史查询：anchorId 为 null 时返回文档全部锚点事件；按 eventId 排序，只读。 */
    @Transactional(readOnly = true)
    public AnchorHistoryResponse listHistory(long documentId, Long anchorId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (anchorId != null) {
            CitationAnchorRow anchor = repository.findAnchor(anchorId)
                    .orElseThrow(() -> ApiException.notFound("锚点不存在: " + anchorId));
            if (anchor.documentId() != documentId) {
                throw ApiException.notFound("锚点不属于文档 " + documentId + ": " + anchorId);
            }
        }
        List<AnchorEventView> views = repository.listAnchorEvents(documentId, anchorId).stream()
                .map(CitationLockService::toEventView).toList();
        return new AnchorHistoryResponse(documentId, views.size(), views);
    }

    /**
     * 诊断查询：对文档全部锚点（含已解除）逐项给出区间是否仍在当前译文内、文本是否仍逐字符保留，
     * 并返回实际字符长度与区间实际文本；只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public ApiDtos.AnchorDiagnosticsResponse diagnostics(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<CitationAnchorRow> anchors = repository.listAllAnchors(documentId);
        List<ApiDtos.AnchorDiagnosticView> views = new ArrayList<>();
        int lockedCount = 0;
        int releasedCount = 0;
        int inconsistentCount = 0;
        for (CitationAnchorRow anchor : anchors) {
            if (Rows.ANCHOR_LOCKED.equals(anchor.status())) {
                lockedCount++;
            } else {
                releasedCount++;
            }
            String content = repository.findTranslation(documentId, anchor.segmentId(), anchor.language())
                    .map(TranslationRow::content).orElse(null);
            int contentLength = content == null ? 0 : content.length();
            boolean inBounds = content != null && anchor.rangeStart() >= 0
                    && anchor.rangeStart() < anchor.rangeEnd() && anchor.rangeEnd() <= contentLength;
            boolean preserved = inBounds
                    && content.substring(anchor.rangeStart(), anchor.rangeEnd()).equals(anchor.anchorText());
            // 已解除锚点仅作证据保留，不计入“待处理不一致”；生效锚点若译文缺失或区间失效即不一致。
            boolean inconsistent = Rows.ANCHOR_LOCKED.equals(anchor.status())
                    && (content == null || !inBounds || !preserved);
            if (inconsistent) {
                inconsistentCount++;
            }
            views.add(new ApiDtos.AnchorDiagnosticView(anchor.anchorId(), anchor.segmentId(), anchor.language(),
                    anchor.citationKey(), anchor.status(), anchor.rangeStart(), anchor.rangeEnd(),
                    contentLength, inBounds, preserved, inBounds
                            ? content.substring(anchor.rangeStart(), anchor.rangeEnd()) : null));
        }
        return new ApiDtos.AnchorDiagnosticsResponse(documentId, views.size(), lockedCount, releasedCount,
                inconsistentCount, views);
    }

    /**
     * 译文修订前的锚点映射校验（不写库）：
     * 无生效锚点时映射必须为空（引用了不存在的锚点视为问题）；
     * 区间未变动且原位文本保留时无需映射；区间变动必须提供该锚点的映射，
     * 映射缺失、重复、锚点不存在、新区间越界/交叉或文本不一致均收集为问题。
     * 返回每个锚点的最终区间计划，供校验完整最终状态后一次性迁移。
     */
    public List<AnchorMigration> planRevision(long documentId, String segmentId, String language,
                                              String newContent, List<AnchorMappingInput> mappings) {
        String normalizedLanguage = normalizeLanguage(language);
        List<CitationAnchorRow> locked = repository.listAnchors(documentId, segmentId, normalizedLanguage).stream()
                .filter(a -> Rows.ANCHOR_LOCKED.equals(a.status())).toList();
        List<IssueView> issues = new ArrayList<>();
        Map<Long, AnchorMappingInput> mappingByAnchor = new LinkedHashMap<>();
        for (AnchorMappingInput mapping : mappings == null ? List.<AnchorMappingInput>of() : mappings) {
            if (mappingByAnchor.put(mapping.anchorId(), mapping) != null) {
                issues.add(issue("ANCHOR_MAPPING_DUPLICATE", segmentId, normalizedLanguage, mapping.anchorId(),
                        "锚点映射重复: anchorId=" + mapping.anchorId()));
            }
        }
        for (AnchorMappingInput mapping : mappingByAnchor.values()) {
            boolean known = locked.stream().anyMatch(a -> a.anchorId() == mapping.anchorId());
            if (!known) {
                issues.add(issue("ANCHOR_NOT_FOUND", segmentId, normalizedLanguage, mapping.anchorId(),
                        "映射的锚点不存在或不在该段落/语言的生效锚点中: anchorId=" + mapping.anchorId()));
            }
            if (mapping.rangeStart() < 0 || mapping.rangeStart() >= mapping.rangeEnd()
                    || mapping.rangeEnd() > newContent.length()) {
                issues.add(issue("ANCHOR_OUT_OF_BOUNDS", segmentId, normalizedLanguage, mapping.anchorId(),
                        "映射区间 [" + mapping.rangeStart() + "," + mapping.rangeEnd()
                                + ") 超出新译文（实际字符长度 " + newContent.length()
                                + "，要求 0 <= start < end <= " + newContent.length() + "，终点超出 "
                                + Math.max(0, mapping.rangeEnd() - newContent.length()) + " 个字符）"));
            }
        }
        List<AnchorMigration> plan = new ArrayList<>();
        for (CitationAnchorRow anchor : locked) {
            AnchorMappingInput mapping = mappingByAnchor.get(anchor.anchorId());
            int finalStart;
            int finalEnd;
            if (mapping == null) {
                finalStart = anchor.rangeStart();
                finalEnd = anchor.rangeEnd();
                boolean inPlace = anchor.rangeStart() >= 0 && anchor.rangeEnd() <= newContent.length()
                        && newContent.regionMatches(anchor.rangeStart(), anchor.anchorText(), 0,
                        anchor.anchorText().length());
                if (!inPlace) {
                    issues.add(issue("ANCHOR_MAPPING_MISSING", segmentId, normalizedLanguage, anchor.anchorId(),
                            "锚点 " + anchor.anchorId() + " 引用文本在原区间 [" + anchor.rangeStart() + ","
                                    + anchor.rangeEnd() + ") 不再保留（新译文实际字符长度 "
                                    + newContent.length() + "），必须提供旧锚点到新区间的一一映射"));
                    continue;
                }
            } else {
                finalStart = mapping.rangeStart();
                finalEnd = mapping.rangeEnd();
                if (finalStart >= 0 && finalEnd <= newContent.length() && finalStart < finalEnd) {
                    String actual = newContent.substring(finalStart, finalEnd);
                    if (!actual.equals(anchor.anchorText())) {
                        issues.add(issue("ANCHOR_TEXT_MISMATCH", segmentId, normalizedLanguage, anchor.anchorId(),
                                "锚点 " + anchor.anchorId() + " 新区间 [" + finalStart + "," + finalEnd
                                        + ") 实际文本 '" + actual + "' 与必须保留的引用文本 '"
                                        + anchor.anchorText() + "' 不一致（要求逐字符保留，实际长度 "
                                        + actual.length() + "，要求长度 " + anchor.anchorText().length()
                                        + "，长度差 " + (actual.length() - anchor.anchorText().length()) + "）"));
                    }
                }
            }
            plan.add(new AnchorMigration(anchor, finalStart, finalEnd));
        }
        for (int i = 0; i < plan.size() && noRangeIssues(issues, segmentId, normalizedLanguage); i++) {
            for (int j = i + 1; j < plan.size(); j++) {
                AnchorMigration a = plan.get(i);
                AnchorMigration b = plan.get(j);
                if (a.newStart() < b.newEnd() && b.newStart() < a.newEnd()) {
                    issues.add(issue("ANCHOR_RANGE_OVERLAP", segmentId, normalizedLanguage,
                            b.anchor().anchorId(),
                            "迁移后锚点区间交叉: anchorId=" + a.anchor().anchorId() + " [" + a.newStart() + ","
                                    + a.newEnd() + ") 与 anchorId=" + b.anchor().anchorId() + " ["
                                    + b.newStart() + "," + b.newEnd() + ")"));
                }
            }
        }
        if (locked.isEmpty() && !mappingByAnchor.isEmpty()) {
            issues.add(issue("ANCHOR_NOT_FOUND", segmentId, normalizedLanguage, null,
                    "该段落语言无生效锚点，却提供了 " + mappingByAnchor.size() + " 条映射"));
        }
        if (!issues.isEmpty()) {
            throw ApiException.validationIssues("锚点映射校验失败，共 " + issues.size() + " 项问题", issues);
        }
        return plan;
    }

    /** 按已校验的迁移计划更新锚点区间并追加 MIGRATED 事件（同一调用方事务内）。 */
    public void applyMigrations(long documentId, List<AnchorMigration> plan, String actorId,
                                int newTranslationVersion, Instant occurredAt) {
        for (AnchorMigration migration : plan) {
            CitationAnchorRow anchor = migration.anchor();
            boolean moved = migration.newStart() != anchor.rangeStart()
                    || migration.newEnd() != anchor.rangeEnd();
            if (!moved) {
                continue;
            }
            repository.updateAnchorRange(anchor.anchorId(), migration.newStart(), migration.newEnd(),
                    newTranslationVersion);
            repository.insertAnchorEvent(new CitationAnchorEventRow(0L, documentId, anchor.anchorId(),
                    anchor.segmentId(), anchor.language(), anchor.citationKey(), EVENT_MIGRATED,
                    migration.newStart(), migration.newEnd(), anchor.rangeStart(), anchor.rangeEnd(),
                    anchor.anchorText(), anchor.lockReason(), actorId,
                    newTranslationVersion, anchor.sourceVersion(), occurredAt));
        }
    }

    /** 锚点迁移计划项：锚点与其在新译文中的最终区间。 */
    public record AnchorMigration(CitationAnchorRow anchor, int newStart, int newEnd) {
    }

    private boolean noRangeIssues(List<IssueView> issues, String segmentId, String language) {
        return issues.stream().noneMatch(i -> "ANCHOR_OUT_OF_BOUNDS".equals(i.code())
                && segmentId.equals(i.segmentId()) && language.equals(i.language()));
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private void bumpDraftVersion(DocumentRow document) {
        int draftVersion = document.draftVersion() + 1;
        repository.updateDraftVersion(document.documentId(), draftVersion);
    }

    private static IssueView issue(String code, String segmentId, String language, Long anchorId, String message) {
        return new IssueView(code, segmentId, language, anchorId, message);
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasLegalRole(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return false;
        }
        for (String role : rolesHeader.split(",")) {
            if (role.trim().equalsIgnoreCase("legal")) {
                return true;
            }
        }
        return false;
    }

    static String formatUtc(Instant instant) {
        return UTC_MICROS.format(instant);
    }

    static AnchorView toView(CitationAnchorRow row) {
        return new AnchorView(row.anchorId(), row.segmentId(), row.language(), row.citationKey(),
                row.rangeStart(), row.rangeEnd(), row.anchorText(), row.lockReason(), row.createdBy(),
                row.translationVersion(), row.sourceVersion(), row.status(), row.releasedBy(),
                row.releaseReason(), formatUtc(row.createdAt()),
                row.releasedAt() == null ? null : formatUtc(row.releasedAt()));
    }

    static AnchorEventView toEventView(CitationAnchorEventRow row) {
        return new AnchorEventView(row.eventId(), row.anchorId(), row.segmentId(), row.language(),
                row.citationKey(), row.eventType(), row.rangeStart(), row.rangeEnd(),
                row.previousStart(), row.previousEnd(), row.anchorText(), row.reason(), row.actorId(),
                row.translationVersion(), row.sourceVersion(), formatUtc(row.occurredAt()));
    }
}
