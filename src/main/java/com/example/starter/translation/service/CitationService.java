package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiDtos.AnchorMappingInput;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.AnchorEventRow;
import com.example.starter.translation.domain.Rows.AnchorRow;
import com.example.starter.translation.domain.Rows.ApprovalRow;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 译文引文锚点服务：锚点登记、修订时区间保留/迁移校验、法务解除，以及明细、历史与只读诊断查询。
 * 所有时间取 UTC；区间为 Unicode 码点偏移、左闭右开。写方法均加入调用方事务，
 * 与译文/术语/发布变更及幂等记录原子提交，失败整体回滚不留半成品。
 */
@Service
public class CitationService {

    /** 解除锚点要求的法务角色标识（请求头 X-Actor-Role 传入，大小写不敏感）。 */
    public static final String LEGAL_ROLE = "LEGAL";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_RELEASED = "RELEASED";
    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final TranslationRepository repository;
    private final Clock clock;

    public CitationService(TranslationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 登记锚点：译文必须处于当前已批准状态（批准仍匹配当前源文与译文版本）；
     * 区间落在当前已批准译文内且不得交叉，同一规范化引用标识在同一段落+语言只能锁定一次（含已解除）。
     * 锚点登记不改变译文内容，草稿/发布版本不变；锚点与事件同一事务写入。
     */
    @Transactional
    public ApiDtos.AnchorRegistrationResponse registerAnchors(long documentId, String segmentId, String language,
                                                              String actorId, ApiDtos.RegisterAnchorsRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        ensureLanguageInDocument(document, normalizedLanguage);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.anchorViolation(
                        "译文不存在，无法登记锚点: " + segmentId + "/" + normalizedLanguage));
        ApprovalRow approval = repository.findApproval(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.anchorViolation(
                        "译文尚未批准，不能登记引文锚点: " + segmentId + "/" + normalizedLanguage));
        if (approval.sourceVersion() != segment.sourceVersion()
                || approval.translationVersion() != translation.translationVersion()) {
            throw ApiException.anchorViolation("译文批准已失效，不能登记引文锚点: " + segmentId + "/"
                    + normalizedLanguage + "，请先重新提交并取得批准");
        }

        List<AnchorRow> existing = repository.listAnchors(documentId, segmentId, normalizedLanguage);
        Set<String> existingKeys = existing.stream().map(AnchorRow::citationKey).collect(Collectors.toSet());
        List<int[]> takenRanges = existing.stream().filter(a -> STATUS_ACTIVE.equals(a.status()))
                .map(a -> new int[]{a.startOffset(), a.endOffset()}).collect(Collectors.toList());

        int contentLength = CitationRules.codePointLength(translation.content());
        Set<String> requestKeys = new HashSet<>();
        List<AnchorRow> created = new ArrayList<>();
        Instant now = Instant.now(clock);
        for (ApiDtos.AnchorInput input : request.anchors()) {
            int start = input.startOffset();
            int end = input.endOffset();
            if (!CitationRules.isValidRange(start, end, contentLength)) {
                throw ApiException.anchorViolation("锚点区间越界或为空: " + input.citationId()
                        + " 提交区间 [" + start + "," + end + ")，要求 0 <= start < end <= 译文码点长度 "
                        + contentLength + "，实际长度 " + contentLength + "，差额 "
                        + Math.max(0, end - contentLength));
            }
            String key = CitationRules.normalizeKey(input.citationId());
            if (!requestKeys.add(key)) {
                throw ApiException.anchorViolation("同一次登记中引用标识重复: " + input.citationId()
                        + "（规范化为 " + key + "）");
            }
            if (existingKeys.contains(key)) {
                throw ApiException.anchorViolation("引用标识在该段落+语言已锁定（含已解除），不能重复锁定: "
                        + input.citationId() + "（规范化为 " + key + "）");
            }
            for (int[] range : takenRanges) {
                if (CitationRules.crosses(start, end, range[0], range[1])) {
                    throw ApiException.anchorViolation("锚点区间与已锁定区间 [" + range[0] + "," + range[1]
                            + ") 交叉: " + input.citationId() + " 提交 [" + start + "," + end + ")");
                }
            }
            String quote = CitationRules.slice(translation.content(), start, end);
            AnchorRow row = new AnchorRow(0, segmentId, normalizedLanguage, key, input.citationId(),
                    start, end, quote, translation.translationVersion(), input.lockReason(), actorId, now,
                    STATUS_ACTIVE, null, null, null, null, null);
            long anchorId = repository.insertAnchor(documentId, row);
            AnchorRow saved = new AnchorRow(anchorId, row.segmentId(), row.language(), row.citationKey(),
                    row.citationKeyRaw(), row.startOffset(), row.endOffset(), row.quoteText(),
                    row.translationVersion(), row.lockReason(), row.createdBy(), row.createdAt(), row.status(),
                    null, null, null, null, null);
            repository.insertAnchorEvent(documentId, new AnchorEventRow(0, anchorId, segmentId,
                    normalizedLanguage, "REGISTERED", translation.translationVersion(), start, end,
                    actorId, input.lockReason(), now));
            created.add(saved);
            existingKeys.add(key);
            takenRanges.add(new int[]{start, end});
        }
        return new ApiDtos.AnchorRegistrationResponse(documentId, document.draftVersion(),
                created.stream().map(this::toView).toList());
    }

    /**
     * 解除锚点：须由不同于创建人的法务角色确认，理由不可变；已解除锚点再次解除 422。
     * 已发布快照中的固化引用不受影响，仍可通过发布版本与锚点历史追溯。
     */
    @Transactional
    public ApiDtos.AnchorView releaseAnchor(long documentId, long anchorId, String actorId, String actorRole,
                                            ApiDtos.ReleaseAnchorRequest request) {
        lockDocument(documentId);
        if (actorRole == null || !LEGAL_ROLE.equals(actorRole.trim().toUpperCase(Locale.ROOT))) {
            throw ApiException.forbidden("解除引文锚点须由法务角色（X-Actor-Role: LEGAL）确认");
        }
        AnchorRow anchor = repository.findAnchorById(documentId, anchorId)
                .orElseThrow(() -> ApiException.notFound("锚点不存在: " + anchorId));
        if (STATUS_RELEASED.equals(anchor.status())) {
            throw ApiException.anchorViolation("锚点已解除，解除操作不可重复: " + anchorId);
        }
        if (anchor.createdBy().equals(actorId)) {
            throw ApiException.forbidden("解除确认人不得是锚点创建人: 创建人 " + anchor.createdBy());
        }
        Instant now = Instant.now(clock);
        int updated = repository.releaseAnchor(documentId, anchorId, actorId, request.reason(), now);
        if (updated == 0) {
            throw ApiException.anchorViolation("锚点状态已变化，解除失败: " + anchorId);
        }
        repository.insertAnchorEvent(documentId, new AnchorEventRow(0, anchorId, anchor.segmentId(),
                anchor.language(), "RELEASED", anchor.translationVersion(), anchor.startOffset(),
                anchor.endOffset(), actorId, request.reason(), now));
        return toView(repository.findAnchorById(documentId, anchorId).orElseThrow());
    }

    /** 查询文档内全部锚点（含已解除），按段落、语言、起始区间排序。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.AnchorView> listAnchors(long documentId) {
        ensureDocumentExists(documentId);
        return repository.listAllAnchors(documentId).stream().map(this::toView).toList();
    }

    /** 查询单段译文的全部锚点（含已解除）。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.AnchorView> listTranslationAnchors(long documentId, String segmentId, String language) {
        ensureDocumentExists(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        return repository.listAnchors(documentId, segmentId, normalizedLanguage).stream()
                .map(this::toView).toList();
    }

    /** 查询锚点明细：当前状态加完整事件历史；锚点不存在 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.AnchorDetailResponse getAnchorDetail(long documentId, long anchorId) {
        ensureDocumentExists(documentId);
        AnchorRow anchor = repository.findAnchorById(documentId, anchorId)
                .orElseThrow(() -> ApiException.notFound("锚点不存在: " + anchorId));
        List<ApiDtos.AnchorEventView> history = repository.listAnchorEvents(documentId, anchorId).stream()
                .map(this::toEventView).toList();
        return new ApiDtos.AnchorDetailResponse(toView(anchor), history);
    }

    /**
     * 只读诊断：逐段落语言核对当前生效锚点与当前译文的一致性，
     * 给出实际锚点数、要求数（等于实际数）、差额及越界、文本漂移、交叉、重复标识明细。
     */
    @Transactional(readOnly = true)
    public ApiDtos.AnchorDiagnosticsResponse diagnose(long documentId) {
        DocumentRow document = ensureDocumentExists(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> t.segmentId() + " " + t.language(), t -> t,
                        (a, b) -> a, LinkedHashMap::new));
        List<ApiDtos.AnchorMappingDiagnostic> items = new ArrayList<>();
        Map<String, List<AnchorRow>> grouped = new LinkedHashMap<>();
        for (AnchorRow anchor : repository.listAllAnchors(documentId)) {
            grouped.computeIfAbsent(anchor.segmentId() + " " + anchor.language(), k -> new ArrayList<>()).add(anchor);
        }
        for (Map.Entry<String, List<AnchorRow>> entry : grouped.entrySet()) {
            String[] parts = entry.getKey().split(" ", 2);
            List<AnchorRow> active = entry.getValue().stream()
                    .filter(a -> STATUS_ACTIVE.equals(a.status())).toList();
            TranslationRow translation = translations.get(entry.getKey());
            String content = translation == null ? "" : translation.content();
            int contentLength = CitationRules.codePointLength(content);
            List<Long> outOfRange = new ArrayList<>();
            List<Long> quoteMismatch = new ArrayList<>();
            List<String> crossingPairs = new ArrayList<>();
            List<String> duplicateKeys = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (AnchorRow anchor : active) {
                if (!CitationRules.isValidRange(anchor.startOffset(), anchor.endOffset(), contentLength)) {
                    outOfRange.add(anchor.anchorId());
                } else if (!CitationRules.slice(content, anchor.startOffset(), anchor.endOffset())
                        .equals(anchor.quoteText())) {
                    quoteMismatch.add(anchor.anchorId());
                }
                if (!keys.add(anchor.citationKey())) {
                    duplicateKeys.add(anchor.citationKey());
                }
            }
            for (int i = 0; i < active.size(); i++) {
                for (int j = i + 1; j < active.size(); j++) {
                    AnchorRow a = active.get(i);
                    AnchorRow b = active.get(j);
                    if (CitationRules.crosses(a.startOffset(), a.endOffset(), b.startOffset(), b.endOffset())) {
                        crossingPairs.add(a.anchorId() + "<->" + b.anchorId());
                    }
                }
            }
            items.add(new ApiDtos.AnchorMappingDiagnostic(parts[0], parts[1], active.size(), active.size(), 0,
                    outOfRange, quoteMismatch, crossingPairs, duplicateKeys));
        }
        return new ApiDtos.AnchorDiagnosticsResponse(documentId, items);
    }

    /**
     * 修订提交前校验锚点映射（不写库）：区间未变动的锚点可无映射保留，
     * 区间变动的锚点必须提供从旧锚点 ID 到新区间的一一映射；映射缺失、重复、指向未知锚点、
     * 新区间越界、引用文本不一致或迁移后区间交叉均 422，错误信息含实际值、要求值或差额。
     *
     * @return 需要迁移的锚点及其新区间（未变动锚点不在其中，但其译文版本号会在应用阶段更新）
     */
    public List<MovedAnchor> validateRevision(String segmentId, String language, String newContent,
                                              List<AnchorRow> activeAnchors,
                                              List<AnchorMappingInput> mappings) {
        int newLength = CitationRules.codePointLength(newContent);
        Map<Long, AnchorMappingInput> mappingById = new HashMap<>();
        if (mappings != null) {
            for (AnchorMappingInput mapping : mappings) {
                if (mappingById.put(mapping.anchorId(), mapping) != null) {
                    throw ApiException.anchorViolation("锚点映射重复: anchorId=" + mapping.anchorId()
                            + " 在 " + segmentId + "/" + language + " 的提交中出现多次，要求每个旧锚点至多一条映射");
                }
            }
        }
        Map<Long, AnchorRow> activeById = activeAnchors.stream()
                .collect(Collectors.toMap(AnchorRow::anchorId, a -> a));
        for (Long mappedId : mappingById.keySet()) {
            if (!activeById.containsKey(mappedId)) {
                throw ApiException.anchorViolation("锚点映射指向未知或已解除锚点: anchorId=" + mappedId
                        + "（" + segmentId + "/" + language + "），该锚点不要求映射");
            }
        }
        List<MovedAnchor> moved = new ArrayList<>();
        int requiredMappings = 0;
        for (AnchorRow anchor : activeAnchors) {
            AnchorMappingInput mapping = mappingById.get(anchor.anchorId());
            if (mapping != null) {
                if (!CitationRules.isValidRange(mapping.startOffset(), mapping.endOffset(), newLength)) {
                    throw ApiException.anchorViolation("锚点 " + anchor.anchorId() + " 迁移区间越界: 提交 ["
                            + mapping.startOffset() + "," + mapping.endOffset() + ")，要求 0 <= start < end <= "
                            + "新译文码点长度 " + newLength + "，实际长度 " + newLength + "，差额 "
                            + Math.max(0, mapping.endOffset() - newLength));
                }
                String newQuote = CitationRules.slice(newContent, mapping.startOffset(), mapping.endOffset());
                if (!newQuote.equals(anchor.quoteText())) {
                    throw ApiException.anchorViolation("锚点 " + anchor.anchorId() + " 引用文本不一致: "
                            + "要求保留原文 " + describe(anchor.quoteText()) + "，新区间实际文本 "
                            + describe(newQuote));
                }
                moved.add(new MovedAnchor(anchor, mapping.startOffset(), mapping.endOffset()));
            } else {
                boolean unchanged = CitationRules.isValidRange(
                        anchor.startOffset(), anchor.endOffset(), newLength)
                        && CitationRules.slice(newContent, anchor.startOffset(), anchor.endOffset())
                        .equals(anchor.quoteText());
                if (!unchanged) {
                    requiredMappings++;
                }
            }
        }
        if (requiredMappings > 0) {
            int provided = mappingById.size() - moved.size();
            throw ApiException.anchorViolation("锚点映射缺失: " + segmentId + "/" + language
                    + " 有 " + requiredMappings + " 个锚点区间已变动但未提供映射；要求映射数 "
                    + requiredMappings + "，实际额外提供 " + Math.max(0, provided) + "，差额 "
                    + (requiredMappings - Math.max(0, provided)));
        }
        for (int i = 0; i < moved.size(); i++) {
            for (int j = i + 1; j < moved.size(); j++) {
                MovedAnchor a = moved.get(i);
                MovedAnchor b = moved.get(j);
                if (CitationRules.crosses(a.startOffset(), a.endOffset(), b.startOffset(), b.endOffset())) {
                    throw ApiException.anchorViolation("迁移后锚点区间交叉: anchorId=" + a.anchor().anchorId()
                            + " [" + a.startOffset() + "," + a.endOffset() + ") 与 anchorId="
                            + b.anchor().anchorId() + " [" + b.startOffset() + "," + b.endOffset() + ")");
                }
            }
        }
        for (MovedAnchor move : moved) {
            for (AnchorRow anchor : activeAnchors) {
                if (anchor.anchorId() == move.anchor().anchorId()) {
                    continue;
                }
                int otherStart = anchor.startOffset();
                int otherEnd = anchor.endOffset();
                boolean otherMoved = false;
                for (MovedAnchor other : moved) {
                    if (other.anchor().anchorId() == anchor.anchorId()) {
                        otherStart = other.startOffset();
                        otherEnd = other.endOffset();
                        otherMoved = true;
                        break;
                    }
                }
                if (!otherMoved && CitationRules.crosses(
                        move.startOffset(), move.endOffset(), otherStart, otherEnd)) {
                    throw ApiException.anchorViolation("迁移后锚点区间与未迁移锚点交叉: anchorId="
                            + move.anchor().anchorId() + " [" + move.startOffset() + "," + move.endOffset()
                            + ") 与 anchorId=" + anchor.anchorId() + " [" + otherStart + "," + otherEnd + ")");
                }
            }
        }
        return moved;
    }

    /**
     * 修订提交后应用锚点结果（与译文 upsert 同事务）：迁移锚点更新区间并追加 MIGRATED 事件，
     * 未迁移锚点区间不变；全部生效锚点的译文版本号更新到新译文版本。
     */
    public void applyRevision(long documentId, String segmentId, String language, int newTranslationVersion,
                              String actorId, List<AnchorRow> activeAnchors, List<MovedAnchor> moved) {
        Instant now = Instant.now(clock);
        Map<Long, MovedAnchor> movedById = moved.stream()
                .collect(Collectors.toMap(m -> m.anchor().anchorId(), m -> m));
        for (AnchorRow anchor : activeAnchors) {
            MovedAnchor move = movedById.get(anchor.anchorId());
            if (move != null) {
                repository.updateAnchorMigration(documentId, anchor.anchorId(), move.startOffset(),
                        move.endOffset(), newTranslationVersion, actorId, now);
                repository.insertAnchorEvent(documentId, new AnchorEventRow(0, anchor.anchorId(), segmentId,
                        language, "MIGRATED", newTranslationVersion, move.startOffset(), move.endOffset(),
                        actorId, null, now));
            } else {
                repository.updateAnchorMigration(documentId, anchor.anchorId(), anchor.startOffset(),
                        anchor.endOffset(), newTranslationVersion,
                        anchor.migratedBy(), anchor.migratedAt());
            }
        }
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private DocumentRow ensureDocumentExists(long documentId) {
        return repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private SegmentRow findSegmentOrThrow(long documentId, String segmentId) {
        return repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
    }

    private static void ensureLanguageInDocument(DocumentRow document, String language) {
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String describe(String text) {
        String compact = text.replace("\n", "\\n");
        return compact.length() <= 60 ? compact : compact.substring(0, 60) + "...";
    }

    private ApiDtos.AnchorView toView(AnchorRow anchor) {
        return new ApiDtos.AnchorView(anchor.anchorId(), anchor.segmentId(), anchor.language(),
                anchor.citationKeyRaw(), anchor.citationKey(), anchor.startOffset(), anchor.endOffset(),
                anchor.quoteText(), anchor.translationVersion(), anchor.lockReason(), anchor.createdBy(),
                UTC_FORMATTER.format(anchor.createdAt()), anchor.status(),
                anchor.migratedBy(), anchor.migratedAt() == null ? null : UTC_FORMATTER.format(anchor.migratedAt()),
                anchor.releasedBy(), anchor.releasedReason(),
                anchor.releasedAt() == null ? null : UTC_FORMATTER.format(anchor.releasedAt()));
    }

    private ApiDtos.AnchorEventView toEventView(AnchorEventRow event) {
        return new ApiDtos.AnchorEventView(event.eventId(), event.anchorId(), event.eventType(),
                event.translationVersion(), event.startOffset(), event.endOffset(), event.actor(),
                event.reason(), UTC_FORMATTER.format(event.eventAt()));
    }

    /** 区间迁移结果：旧锚点与新区间（左闭右开码点偏移）。 */
    public record MovedAnchor(AnchorRow anchor, int startOffset, int endOffset) {
    }
}
