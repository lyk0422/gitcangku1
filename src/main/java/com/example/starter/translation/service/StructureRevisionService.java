package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiDtos.FragmentBoundaryView;
import com.example.starter.translation.api.ApiDtos.FragmentInput;
import com.example.starter.translation.api.ApiDtos.LanguageLineageView;
import com.example.starter.translation.api.ApiDtos.NewSegmentInput;
import com.example.starter.translation.api.ApiDtos.NewSegmentMappingInput;
import com.example.starter.translation.api.ApiDtos.ReferenceCandidateView;
import com.example.starter.translation.api.ApiDtos.SegmentLinkView;
import com.example.starter.translation.api.ApiDtos.StructureChangeSummaryView;
import com.example.starter.translation.api.ApiDtos.StructureChangeView;
import com.example.starter.translation.api.ApiDtos.StructureRevisionRequest;
import com.example.starter.translation.api.ApiDtos.StructureRevisionResponse;
import com.example.starter.translation.api.ApiDtos.StructureSegmentView;
import com.example.starter.translation.api.ApiDtos.StructureView;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentLineageRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.StructureChangeRow;
import com.example.starter.translation.domain.Rows.TranslationLineageRow;
import com.example.starter.translation.domain.Rows.TranslationReferenceRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 源段结构修订与跨语言血缘服务。
 *
 * <p>一次结构修订事务（changeKey 全局唯一）把一个当前段拆为 2~5 段（SPLIT），
 * 或把 2~5 个连续当前段合为一段（MERGE），两种操作不能混合。提交携带期望文档草稿版本、
 * 各旧源段版本与涉及语言当前术语版本，任一不符 409；映射缺漏/重复、语言集合不完整等 422，
 * 失败整事务回滚，无部分新段、changeKey 不占键。</p>
 *
 * <p>成功后原子生成新文档版本：旧段标记 SUPERSEDED 并保留源文/译文/批准血缘，新段为 CURRENT
 * 且无译文无批准（须重新编辑与批准）；每个目标语言按有序来源映射把旧译文片段拼接为 REFERENCE
 * 候选并记录片段边界。历史发布快照不变。</p>
 *
 * <p>所有写操作与既有写操作一样先对文档行加 FOR UPDATE 行锁，结构修订与源文/译文/批准/术语/
 * 发布并发串行化，配合期望版本校验保证不会出现混合文档版本。</p>
 */
@Service
public class StructureRevisionService {

    public static final String SPLIT = "SPLIT";
    public static final String MERGE = "MERGE";

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;

    public StructureRevisionService(TranslationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交结构修订事务。
     */
    @Transactional
    public StructureRevisionResponse revise(long documentId, StructureRevisionRequest request) {
        DocumentRow document = repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));

        String changeType = normalizeChangeType(request.changeType());
        // changeKey 全局唯一：同 changeKey 的另一次提交一律 409（同 requestId 同参重放由幂等执行器提前返回）
        if (repository.findStructureChange(request.changeKey()).isPresent()) {
            throw ApiException.conflict("changeKey 已使用: " + request.changeKey());
        }

        if (document.draftVersion() != request.expectedDocumentVersion()) {
            throw ApiException.conflict("文档版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedDocumentVersion() + " 不一致");
        }
        if (document.termVersion() != request.expectedTermVersion()) {
            throw ApiException.conflict("术语版本冲突：当前术语版本 " + document.termVersion()
                    + "，与期望的 " + request.expectedTermVersion() + " 不一致");
        }

        List<String> oldIds = request.segmentIds();
        List<NewSegmentInput> newSegments = request.newSegments();
        validateCardinality(changeType, oldIds.size(), newSegments.size());

        // 旧段必须全部存在且为当前段；合并还必须按当前结构序号连续
        List<SegmentRow> oldSegments = loadAndValidateOldSegments(documentId, changeType, oldIds);
        validateExpectedSourceVersions(oldSegments, request.expectedSourceVersions());

        List<String> newIds = validateNewSegments(documentId, newSegments);
        Map<String, List<NewSegmentMappingInput>> mappings =
                normalizeMappings(document, request.languageMappings(), newIds);

        // 逐语言校验片段映射并计算参考候选与边界（此刻旧译文内容已锁定）
        Map<String, List<TranslationReferenceRow>> referencesByLanguage = new LinkedHashMap<>();
        for (String language : document.targetLanguages()) {
            referencesByLanguage.put(language,
                    buildReferences(documentId, changeType, language, oldSegments, newSegments,
                            mappings.get(language)));
        }

        int newVersion = document.draftVersion() + 1;
        applyStructureChange(documentId, request.changeKey(), changeType, document.draftVersion(), newVersion,
                document.termVersion(), oldSegments, newSegments, referencesByLanguage);

        List<ReferenceCandidateView> referenceViews = new ArrayList<>();
        for (String language : document.targetLanguages()) {
            for (TranslationReferenceRow row : referencesByLanguage.get(language)) {
                referenceViews.add(toReferenceView(row));
            }
        }
        return new StructureRevisionResponse(documentId, request.changeKey(), changeType, newVersion,
                List.copyOf(oldIds), List.copyOf(newIds), document.targetLanguages(), referenceViews);
    }

    /** 查询当前结构：版本信息与按序排列的当前段，只读。 */
    @Transactional(readOnly = true)
    public StructureView getStructure(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<StructureSegmentView> segments = repository.listCurrentSegments(documentId).stream()
                .map(s -> new StructureSegmentView(s.segmentId(), s.sourceText(), s.sourceVersion(),
                        s.position(), s.createdChangeKey()))
                .toList();
        return new StructureView(documentId, document.draftVersion(), document.termVersion(),
                document.targetLanguages(), segments);
    }

    /** 查询文档的全部结构修订摘要，按生成的文档版本排序，只读。 */
    @Transactional(readOnly = true)
    public List<StructureChangeSummaryView> listChanges(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return repository.listStructureChanges(documentId).stream()
                .map(c -> new StructureChangeSummaryView(c.changeKey(), c.changeType(), c.documentVersion(),
                        c.expectedDocumentVersion(), c.expectedTermVersion()))
                .toList();
    }

    /** 查询单次结构修订详情：源段血缘、跨语言片段血缘与参考候选，只读。 */
    @Transactional(readOnly = true)
    public StructureChangeView getChange(long documentId, String changeKey) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        StructureChangeRow change = repository.findStructureChange(changeKey)
                .filter(c -> c.documentId() == documentId)
                .orElseThrow(() -> ApiException.notFound("结构修订不存在: " + changeKey));

        List<SegmentLineageRow> segmentRows = repository.listSegmentLineage(documentId, changeKey);
        List<SegmentLinkView> segmentLinks = segmentRows.stream()
                .map(r -> new SegmentLinkView(r.oldSegmentId(), r.newSegmentId(), r.ordinal()))
                .toList();
        List<String> oldIds = segmentRows.stream().map(SegmentLineageRow::oldSegmentId).distinct().toList();
        List<String> newIds = segmentRows.stream().map(SegmentLineageRow::newSegmentId).distinct().toList();

        List<LanguageLineageView> languageLineage = buildLanguageLineage(documentId, changeKey);

        List<ReferenceCandidateView> references = repository.listReferencesByChange(documentId, changeKey).stream()
                .map(this::toReferenceView)
                .toList();
        return new StructureChangeView(change.changeKey(), change.changeType(), change.documentVersion(),
                change.expectedDocumentVersion(), change.expectedTermVersion(), oldIds, newIds,
                segmentLinks, languageLineage, references);
    }

    /**
     * 查询单段跨语言血缘：SUPERSEDED 段返回 FORWARD（到新段），结构修订产生的当前段返回 BACKWARD，
     * 未参与结构修订的段返回 NONE。只读。
     */
    @Transactional(readOnly = true)
    public ApiDtos.SegmentLineageView getSegmentLineage(long documentId, String segmentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        SegmentRow segment = repository.findAnySegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));

        String direction = "NONE";
        String changeKey = null;
        if (segment.supersededChangeKey() != null) {
            direction = "FORWARD";
            changeKey = segment.supersededChangeKey();
        } else if (segment.createdChangeKey() != null) {
            direction = "BACKWARD";
            changeKey = segment.createdChangeKey();
        }
        if (changeKey == null) {
            return new ApiDtos.SegmentLineageView(documentId, segmentId, segment.status(), "NONE",
                    null, List.of(), List.of());
        }

        List<SegmentLinkView> segmentLinks = repository.listSegmentLineage(documentId, changeKey).stream()
                .map(r -> new SegmentLinkView(r.oldSegmentId(), r.newSegmentId(), r.ordinal()))
                .toList();
        List<LanguageLineageView> languageLineage = buildLanguageLineage(documentId, changeKey);
        return new ApiDtos.SegmentLineageView(documentId, segmentId, segment.status(), direction, changeKey,
                segmentLinks, languageLineage);
    }

    private void validateCardinality(String changeType, int oldCount, int newCount) {
        if (SPLIT.equals(changeType)) {
            if (oldCount != 1) {
                throw ApiException.unprocessable("SPLIT 只能修订一个旧段，实际为 " + oldCount + " 个");
            }
            if (newCount < 2 || newCount > 5) {
                throw ApiException.unprocessable("SPLIT 必须把一个段拆为 2~5 个新段，实际为 " + newCount + " 个");
            }
        } else {
            if (oldCount < 2 || oldCount > 5) {
                throw ApiException.unprocessable("MERGE 必须合并 2~5 个旧段，实际为 " + oldCount + " 个");
            }
            if (newCount != 1) {
                throw ApiException.unprocessable("MERGE 只能产生一个新段，实际为 " + newCount + " 个");
            }
        }
    }

    private List<SegmentRow> loadAndValidateOldSegments(long documentId, String changeType, List<String> oldIds) {
        Set<String> distinct = new HashSet<>();
        for (String oldId : oldIds) {
            if (!distinct.add(oldId)) {
                throw ApiException.unprocessable("旧段重复: " + oldId);
            }
        }
        List<SegmentRow> oldSegments = new ArrayList<>();
        for (String oldId : oldIds) {
            SegmentRow segment = repository.findAnySegment(documentId, oldId)
                    .orElseThrow(() -> ApiException.unprocessable("旧段不存在或已被取代: " + oldId));
            if (!"CURRENT".equals(segment.status())) {
                throw ApiException.unprocessable("旧段不是当前段: " + oldId);
            }
            oldSegments.add(segment);
        }
        if (MERGE.equals(changeType)) {
            // 提交顺序即结构顺序，且序号必须连续
            for (int i = 1; i < oldSegments.size(); i++) {
                if (oldSegments.get(i).position() != oldSegments.get(i - 1).position() + 1) {
                    throw ApiException.unprocessable(
                            "MERGE 输入的旧段必须按当前结构顺序连续: "
                                    + oldSegments.get(i - 1).segmentId() + " -> " + oldSegments.get(i).segmentId());
                }
            }
        }
        return oldSegments;
    }

    private void validateExpectedSourceVersions(List<SegmentRow> oldSegments,
                                                Map<String, Integer> expectedSourceVersions) {
        Set<String> expectedKeys = new HashSet<>(expectedSourceVersions.keySet());
        for (SegmentRow segment : oldSegments) {
            Integer expected = expectedSourceVersions.get(segment.segmentId());
            if (expected == null) {
                throw ApiException.unprocessable("缺少旧源段版本: " + segment.segmentId());
            }
            if (expected != segment.sourceVersion()) {
                throw ApiException.conflict("旧源段版本冲突: " + segment.segmentId() + " 当前版本 "
                        + segment.sourceVersion() + "，与期望的 " + expected + " 不一致");
            }
            expectedKeys.remove(segment.segmentId());
        }
        if (!expectedKeys.isEmpty()) {
            throw ApiException.unprocessable("expectedSourceVersions 包含不属于本次修订的段: " + expectedKeys);
        }
    }

    private List<String> validateNewSegments(long documentId, List<NewSegmentInput> newSegments) {
        List<String> newIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NewSegmentInput input : newSegments) {
            if (!seen.add(input.segmentId())) {
                throw ApiException.unprocessable("新段 segmentId 重复: " + input.segmentId());
            }
            // 新段键在文档全部历史段（含已取代段）中唯一
            if (repository.findAnySegment(documentId, input.segmentId()).isPresent()) {
                throw ApiException.conflict("新段键已存在（含历史段）: " + input.segmentId());
            }
            newIds.add(input.segmentId());
        }
        return newIds;
    }

    /** 归一化语言映射：语言集合必须与文档目标语言完全一致，且每个新段恰好一条映射。 */
    private Map<String, List<NewSegmentMappingInput>> normalizeMappings(
            DocumentRow document, Map<String, List<NewSegmentMappingInput>> raw, List<String> newIds) {
        Map<String, List<NewSegmentMappingInput>> normalized = new LinkedHashMap<>();
        Set<String> languages = new HashSet<>();
        for (Map.Entry<String, List<NewSegmentMappingInput>> entry : raw.entrySet()) {
            String language = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("映射语言不在文档目标语言中: " + language);
            }
            if (!languages.add(language)) {
                throw ApiException.unprocessable("映射语言重复: " + language);
            }
            List<NewSegmentMappingInput> mappings = entry.getValue();
            Set<String> mappedSegments = new HashSet<>();
            for (NewSegmentMappingInput mapping : mappings) {
                if (!newIds.contains(mapping.newSegmentId())) {
                    throw ApiException.unprocessable("映射目标不是本次新段: " + mapping.newSegmentId());
                }
                if (!mappedSegments.add(mapping.newSegmentId())) {
                    throw ApiException.unprocessable("新段映射重复: " + mapping.newSegmentId());
                }
            }
            if (mappedSegments.size() != newIds.size()) {
                List<String> missing = newIds.stream().filter(id -> !mappedSegments.contains(id)).toList();
                throw ApiException.unprocessable("语言 " + language + " 缺少新段映射: " + missing);
            }
            normalized.put(language, mappings);
        }
        if (!languages.equals(new HashSet<>(document.targetLanguages()))) {
            List<String> missing = document.targetLanguages().stream()
                    .filter(lang -> !languages.contains(lang)).toList();
            throw ApiException.unprocessable("语言集合不完整，缺少: " + missing);
        }
        return normalized;
    }

    /**
     * 按有序片段映射校验边界并拼接参考候选：
     * SPLIT 时各新段片段必须有序、无重叠/缺口地完整覆盖旧译文；
     * MERGE 时唯一新段必须按旧段结构顺序各取整段译文一次。旧译文缺失按空串处理并保留边界。
     */
    private List<TranslationReferenceRow> buildReferences(long documentId, String changeType, String language,
                                                         List<SegmentRow> oldSegments,
                                                         List<NewSegmentInput> newSegments,
                                                         List<NewSegmentMappingInput> mappings) {
        Map<String, TranslationRow> oldTranslations = new LinkedHashMap<>();
        for (SegmentRow old : oldSegments) {
            repository.findTranslation(documentId, old.segmentId(), language)
                    .ifPresent(row -> oldTranslations.put(old.segmentId(), row));
        }
        // 按 newSegments 的提交顺序排列映射
        Map<String, NewSegmentMappingInput> mappingByNewSegment = new LinkedHashMap<>();
        for (NewSegmentMappingInput mapping : mappings) {
            mappingByNewSegment.put(mapping.newSegmentId(), mapping);
        }

        List<TranslationReferenceRow> rows = new ArrayList<>();
        if (SPLIT.equals(changeType)) {
            SegmentRow old = oldSegments.get(0);
            String oldContent = oldTranslations.containsKey(old.segmentId())
                    ? oldTranslations.get(old.segmentId()).content() : "";
            int expected = 0;
            for (NewSegmentInput newSegment : newSegments) {
                NewSegmentMappingInput mapping = mappingByNewSegment.get(newSegment.segmentId());
                List<FragmentInput> fragments = mapping.fragments();
                if (oldContent.isEmpty()) {
                    if (!fragments.isEmpty()) {
                        throw ApiException.unprocessable("语言 " + language + " 新段 " + newSegment.segmentId()
                                + "：旧译文为空时不能携带片段映射");
                    }
                    rows.add(referenceRow(newSegment.segmentId(), language, List.of()));
                    continue;
                }
                if (fragments.isEmpty()) {
                    throw ApiException.unprocessable("语言 " + language + " 新段 " + newSegment.segmentId()
                            + "：片段映射缺漏");
                }
                List<ResolvedFragment> resolved = new ArrayList<>();
                for (FragmentInput fragment : fragments) {
                    if (!old.segmentId().equals(fragment.oldSegmentId())) {
                        throw ApiException.unprocessable("语言 " + language + " 新段 " + newSegment.segmentId()
                                + "：SPLIT 片段只能来自被拆旧段 " + old.segmentId());
                    }
                    ResolvedFragment rf = resolveFragment(fragment, oldContent, language, newSegment.segmentId());
                    if (rf.startOffset() != expected) {
                        throw ApiException.unprocessable("语言 " + language + " 新段 " + newSegment.segmentId()
                                + "：片段未连续覆盖旧译文（期望起点 " + expected + "，实际 " + rf.startOffset()
                                + "），存在缺漏或重叠");
                    }
                    expected = rf.endOffset();
                    resolved.add(rf);
                }
                rows.add(referenceRow(newSegment.segmentId(), language, resolved));
            }
            int totalLength = oldContent.length();
            if (expected != totalLength) {
                throw ApiException.unprocessable("语言 " + language + "：片段映射未完整覆盖旧译文，已覆盖到 "
                        + expected + "，总长度 " + totalLength);
            }
        } else {
            NewSegmentInput merged = newSegments.get(0);
            NewSegmentMappingInput mapping = mappingByNewSegment.get(merged.segmentId());
            List<FragmentInput> fragments = mapping.fragments();
            if (fragments.size() != oldSegments.size()) {
                throw ApiException.unprocessable("语言 " + language + "：MERGE 必须按旧段顺序为每个旧段提供恰好"
                        + "一个整段片段，实际片段数 " + fragments.size() + "，旧段数 " + oldSegments.size());
            }
            List<ResolvedFragment> resolved = new ArrayList<>();
            for (int i = 0; i < oldSegments.size(); i++) {
                SegmentRow old = oldSegments.get(i);
                FragmentInput fragment = fragments.get(i);
                if (!old.segmentId().equals(fragment.oldSegmentId())) {
                    throw ApiException.unprocessable("语言 " + language + "：MERGE 片段次序与连续旧段不一致，位置 "
                            + (i + 1) + " 应为 " + old.segmentId() + "，实际为 " + fragment.oldSegmentId());
                }
                String oldContent = oldTranslations.containsKey(old.segmentId())
                        ? oldTranslations.get(old.segmentId()).content() : "";
                resolved.add(resolveWholeFragment(fragment, oldContent, language, merged.segmentId()));
            }
            rows.add(referenceRow(merged.segmentId(), language, resolved));
        }
        return rows;
    }

    private ResolvedFragment resolveFragment(FragmentInput fragment, String oldContent, String language,
                                             String newSegmentId) {
        int start = fragment.startOffset() == null ? 0 : fragment.startOffset();
        int end = fragment.endOffset() == null ? oldContent.length() : fragment.endOffset();
        if (start < 0 || end < start || end > oldContent.length()) {
            throw ApiException.unprocessable("语言 " + language + " 新段 " + newSegmentId + "：片段边界越界 ["
                    + start + "," + end + ")，旧译文长度 " + oldContent.length());
        }
        return new ResolvedFragment(fragment.oldSegmentId(), start, end, oldContent.substring(start, end));
    }

    private ResolvedFragment resolveWholeFragment(FragmentInput fragment, String oldContent, String language,
                                                  String newSegmentId) {
        if (fragment.startOffset() != null || fragment.endOffset() != null) {
            Integer start = fragment.startOffset();
            Integer end = fragment.endOffset();
            if (start == null || end == null || start != 0 || end != oldContent.length()) {
                throw ApiException.unprocessable("语言 " + language + " 新段 " + newSegmentId
                        + "：MERGE 片段必须整段引用旧译文");
            }
        }
        return new ResolvedFragment(fragment.oldSegmentId(), 0, oldContent.length(), oldContent);
    }

    private TranslationReferenceRow referenceRow(String newSegmentId, String language,
                                                 List<ResolvedFragment> resolved) {
        StringBuilder content = new StringBuilder();
        List<FragmentBoundaryView> boundaries = new ArrayList<>();
        int ordinal = 1;
        for (ResolvedFragment rf : resolved) {
            content.append(rf.text());
            boundaries.add(new FragmentBoundaryView(rf.oldSegmentId(), ordinal++,
                    rf.startOffset(), rf.endOffset()));
        }
        return new TranslationReferenceRow(newSegmentId, language, null, content.toString(),
                writeBoundaries(boundaries));
    }

    /** 落库全部结构变更，与文档版本递增同一事务。 */
    private void applyStructureChange(long documentId, String changeKey, String changeType,
                                      int expectedDocumentVersion, int newVersion, int expectedTermVersion,
                                      List<SegmentRow> oldSegments, List<NewSegmentInput> newSegments,
                                      Map<String, List<TranslationReferenceRow>> referencesByLanguage) {
        try {
            repository.insertStructureChange(new StructureChangeRow(changeKey, documentId, changeType,
                    expectedDocumentVersion, newVersion, expectedTermVersion));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("changeKey 已使用: " + changeKey);
        }

        if (SPLIT.equals(changeType)) {
            SegmentRow old = oldSegments.get(0);
            int basePosition = old.position();
            // 后序当前段后移，为新段腾出连续位置（旧段自身保留原序号供溯源）
            shiftPositions(documentId, basePosition + 1, Integer.MAX_VALUE, newSegments.size() - 1);
            repository.supersedeSegment(documentId, old.segmentId(), changeKey);
            int ordinal = 1;
            int position = basePosition;
            for (NewSegmentInput input : newSegments) {
                repository.insertStructuredSegment(documentId, input.segmentId(), input.sourceText(),
                        position++, changeKey);
                repository.insertSegmentLineage(documentId,
                        new SegmentLineageRow(changeKey, old.segmentId(), input.segmentId(), ordinal++));
            }
        } else {
            int firstPosition = oldSegments.get(0).position();
            int lastPosition = oldSegments.get(oldSegments.size() - 1).position();
            shiftPositions(documentId, lastPosition + 1, Integer.MAX_VALUE, -(oldSegments.size() - 1));
            int ordinal = 1;
            for (SegmentRow old : oldSegments) {
                repository.supersedeSegment(documentId, old.segmentId(), changeKey);
                repository.insertSegmentLineage(documentId,
                        new SegmentLineageRow(changeKey, old.segmentId(), newSegments.get(0).segmentId(),
                                ordinal++));
            }
            repository.insertStructuredSegment(documentId, newSegments.get(0).segmentId(),
                    newSegments.get(0).sourceText(), firstPosition, changeKey);
        }

        for (Map.Entry<String, List<TranslationReferenceRow>> entry : referencesByLanguage.entrySet()) {
            String language = entry.getKey();
            for (TranslationReferenceRow reference : entry.getValue()) {
                TranslationReferenceRow stored = new TranslationReferenceRow(reference.newSegmentId(), language,
                        changeKey, reference.content(), reference.boundaryJson());
                repository.insertTranslationReference(documentId, stored);
                for (FragmentBoundaryView boundary : readBoundaries(stored.boundaryJson())) {
                    repository.insertTranslationLineage(documentId, new TranslationLineageRow(
                            changeKey, language, boundary.oldSegmentId(), reference.newSegmentId(),
                            boundary.ordinal(), boundary.startOffset(), boundary.endOffset()));
                }
            }
        }
        repository.updateDraftVersion(documentId, newVersion);
    }

    /** 将位置落在区间内（含端点）的当前段序号整体平移 delta；MAX_VALUE 上界表示到末尾。 */
    private void shiftPositions(long documentId, int fromPosition, int toPosition, int delta) {
        List<SegmentRow> currents = repository.listCurrentSegments(documentId);
        for (SegmentRow segment : currents) {
            if (segment.position() >= fromPosition && segment.position() <= toPosition) {
                repository.updatePosition(documentId, segment.segmentId(), segment.position() + delta);
            }
        }
    }

    private List<LanguageLineageView> buildLanguageLineage(long documentId, String changeKey) {
        Map<String, Map<String, List<TranslationLineageRow>>> grouped = new LinkedHashMap<>();
        for (TranslationLineageRow row : repository.listTranslationLineage(documentId, changeKey)) {
            grouped.computeIfAbsent(row.language(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(row.newSegmentId(), k -> new ArrayList<>()).add(row);
        }
        List<LanguageLineageView> views = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<TranslationLineageRow>>> languageEntry : grouped.entrySet()) {
            for (Map.Entry<String, List<TranslationLineageRow>> segmentEntry : languageEntry.getValue().entrySet()) {
                List<FragmentBoundaryView> fragments = segmentEntry.getValue().stream()
                        .map(r -> new FragmentBoundaryView(r.oldSegmentId(), r.ordinal(),
                                r.startOffset(), r.endOffset()))
                        .toList();
                views.add(new LanguageLineageView(languageEntry.getKey(), segmentEntry.getKey(), fragments));
            }
        }
        return views;
    }

    private ReferenceCandidateView toReferenceView(TranslationReferenceRow row) {
        return new ReferenceCandidateView(row.newSegmentId(), row.language(), row.content(),
                readBoundaries(row.boundaryJson()));
    }

    private String writeBoundaries(List<FragmentBoundaryView> boundaries) {
        try {
            return objectMapper.writeValueAsString(boundaries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("片段边界序列化失败", e);
        }
    }

    private List<FragmentBoundaryView> readBoundaries(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<FragmentBoundaryView>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("片段边界反序列化失败", e);
        }
    }

    private static String normalizeChangeType(String changeType) {
        String normalized = changeType.trim().toUpperCase(Locale.ROOT);
        if (!SPLIT.equals(normalized) && !MERGE.equals(normalized)) {
            throw ApiException.unprocessable("changeType 只能是 SPLIT 或 MERGE: " + changeType);
        }
        return normalized;
    }

    /** 片段边界解析结果：来源旧段、起止偏移与实际切出的文本。 */
    private record ResolvedFragment(String oldSegmentId, int startOffset, int endOffset, String text) {
    }
}
