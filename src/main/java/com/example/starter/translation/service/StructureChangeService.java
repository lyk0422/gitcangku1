package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiDtos.LanguageMapping;
import com.example.starter.translation.api.ApiDtos.LanguageTermVersion;
import com.example.starter.translation.api.ApiDtos.NewSegmentInput;
import com.example.starter.translation.api.ApiDtos.OldSegmentVersion;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReferenceCandidateRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.SourceLineageRow;
import com.example.starter.translation.domain.Rows.StructureChangeRow;
import com.example.starter.translation.domain.Rows.TranslationLineageRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 结构修订事务服务：一次 changeKey 把一个当前源段拆成 2~5 段，或把 2~5 个连续当前段合为一段。
 * 全程在文档行 FOR UPDATE 锁与单一事务内完成校验与落库：旧段 SUPERSEDED、新段写入、
 * 源段与各语言译文双向血缘保存、旧译文按有序映射拼接为 REFERENCE 候选并记录片段边界，
 * 原子生成新文档草稿版本；任何校验失败整体回滚，不产生部分新段。
 */
@Service
public class StructureChangeService {

    private static final int MAX_CHANGE_SIZE = 5;

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;

    public StructureChangeService(TranslationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交结构修订。expectedDocumentVersion 须等于当前草稿版本；changeKey 文档内唯一；
     * 各旧源段版本、涉及语言当前术语版本均须匹配；映射须完整、无缺漏/重复且语言集合完整。
     */
    @Transactional
    public ApiDtos.StructureChangeResponse changeStructure(long documentId, ApiDtos.StructureChangeRequest request) {
        DocumentRow document = repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        if (repository.findStructureChange(documentId, request.changeKey()).isPresent()) {
            throw ApiException.conflict("changeKey 已使用: " + request.changeKey());
        }
        if (document.draftVersion() != request.expectedDocumentVersion()) {
            throw ApiException.conflict("文档版本冲突：当前草稿版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedDocumentVersion() + " 不一致");
        }

        List<SegmentRow> currentSegments = repository.listSegments(documentId);
        List<NewSegmentInput> newSegments = validateNewSegments(request.newSegments());
        List<OldSegmentVersion> oldSegments = request.oldSegments();
        String changeType = resolveChangeType(oldSegments.size(), newSegments.size());

        Map<String, SegmentRow> currentById = currentSegments.stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        List<SegmentRow> oldRows = validateOldSegments(documentId, oldSegments, currentById, changeType);
        List<String> oldIds = oldRows.stream().map(SegmentRow::segmentId).toList();
        List<String> newIds = newSegments.stream().map(NewSegmentInput::newSegmentId).toList();
        validateNewKeys(newIds, oldIds);
        Map<String, Integer> termVersionByLanguage = validateLanguageTermVersions(document,
                request.languageTermVersions());
        validateMappings(request.mappings(), document.targetLanguages(), newIds, oldIds);

        List<TranslationRow> allTranslations = repository.listTranslations(documentId);
        Map<String, TranslationRow> translationIndex = allTranslations.stream()
                .collect(Collectors.toMap(t -> t.segmentId() + " " + t.language(), Function.identity()));
        Map<String, LanguageMapping> mappingByLanguage = request.mappings().stream()
                .collect(Collectors.toMap(m -> normalizeLanguage(m.language()), Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        int draftVersion = document.draftVersion() + 1;
        // 提交闸门：原子校验草稿版本与术语版本自加锁以来未变，否则 409 整体回滚，不产生混合文档版本。
        if (repository.incrementDraftVersionIfMatches(documentId, request.expectedDocumentVersion(),
                document.termVersion()) == 0) {
            throw ApiException.conflict("文档版本冲突：草稿或术语版本已被并发修改，与提交期望不一致");
        }
        try {
            repository.insertStructureChange(documentId,
                    new StructureChangeRow(request.changeKey(), changeType, draftVersion));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("changeKey 已使用: " + request.changeKey());
        }

        // 旧段淘汰并按“前段 + 新段 + 后段”重排当前结构位置。
        int replaceStart = currentSegments.indexOf(oldRows.get(0));
        for (SegmentRow old : oldRows) {
            repository.markSegmentSuperseded(documentId, old.segmentId());
        }
        List<String> rebuiltOrder = new ArrayList<>();
        for (int i = 0; i < replaceStart; i++) {
            rebuiltOrder.add(currentSegments.get(i).segmentId());
        }
        rebuiltOrder.addAll(newIds);
        for (int i = replaceStart + oldRows.size(); i < currentSegments.size(); i++) {
            rebuiltOrder.add(currentSegments.get(i).segmentId());
        }
        Set<String> newIdSet = new LinkedHashSet<>(newIds);
        Map<String, NewSegmentInput> newInputById = newSegments.stream()
                .collect(Collectors.toMap(NewSegmentInput::newSegmentId, Function.identity()));
        for (int i = 0; i < rebuiltOrder.size(); i++) {
            String segmentId = rebuiltOrder.get(i);
            int position = i + 1;
            if (newIdSet.contains(segmentId)) {
                NewSegmentInput input = newInputById.get(segmentId);
                repository.insertNewSegment(documentId, segmentId, input.sourceText(), position);
            } else {
                repository.updateSegmentPosition(documentId, segmentId, position);
            }
        }

        // 源段双向血缘：拆分时每个新段来源唯一旧段；合并时新段按结构顺序来源全部旧段。
        for (String newId : newIds) {
            List<String> sources = "SPLIT".equals(changeType) ? List.of(oldIds.get(0)) : oldIds;
            for (int i = 0; i < sources.size(); i++) {
                repository.insertSourceLineage(documentId,
                        new SourceLineageRow(newId, sources.get(i), i + 1));
            }
        }

        // 各语言译文血缘与 REFERENCE 候选：按有序映射拼接旧译文并记录片段边界。
        for (String language : document.targetLanguages()) {
            LanguageMapping mapping = mappingByLanguage.get(language);
            Map<String, List<String>> sourcesByNew = mapping.mappings().stream()
                    .collect(Collectors.toMap(m -> m.newSegmentId(), ApiDtos.NewSegmentMapping::oldSegmentIds,
                            (a, b) -> a, LinkedHashMap::new));
            for (String newId : newIds) {
                List<String> sources = sourcesByNew.get(newId);
                StringBuilder content = new StringBuilder();
                List<Integer> boundaries = new ArrayList<>();
                for (String oldId : sources) {
                    TranslationRow fragment = translationIndex.get(oldId + " " + language);
                    if (fragment == null) {
                        throw ApiException.unprocessable(
                                "旧译文缺失，无法生成 REFERENCE 候选: " + oldId + "/" + language);
                    }
                    content.append(fragment.content());
                    boundaries.add(content.length());
                    repository.insertTranslationLineage(documentId,
                            new TranslationLineageRow(newId, language, oldId, boundaries.size()));
                }
                repository.insertReferenceCandidate(documentId, new ReferenceCandidateRow(
                        newId, language, content.toString(), writeBoundaries(boundaries)));
            }
        }

        return new ApiDtos.StructureChangeResponse(documentId, request.changeKey(), changeType,
                draftVersion, newIds);
    }

    /** 查询当前结构：文档版本、目标语言与按顺序排列的当前段落，只读。 */
    @Transactional(readOnly = true)
    public ApiDtos.StructureResponse getStructure(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ApiDtos.StructureSegmentView> segments = repository.listSegments(documentId).stream()
                .map(s -> new ApiDtos.StructureSegmentView(
                        s.segmentId(), s.sourceText(), s.sourceVersion(), s.position()))
                .toList();
        return new ApiDtos.StructureResponse(documentId, document.draftVersion(),
                document.targetLanguages(), segments);
    }

    /**
     * 查询跨语言血缘：源段双向血缘边，以及每语言新段的有序来源与 REFERENCE 候选（含片段边界）。
     * segmentId 为空时返回文档全部结构修订血缘；指定时仅返回该段（不存在 404）。只读。
     */
    @Transactional(readOnly = true)
    public ApiDtos.LineageResponse getLineage(long documentId, String segmentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<String> scopeNewIds;
        List<ApiDtos.LineageEdgeView> sourceEdges;
        if (segmentId == null || segmentId.isBlank()) {
            sourceEdges = collectAllSourceEdges(documentId);
            scopeNewIds = sourceEdges.stream().map(ApiDtos.LineageEdgeView::newSegmentId).distinct().toList();
        } else {
            SegmentRow segment = repository.findAnySegment(documentId, segmentId)
                    .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
            scopeNewIds = List.of(segment.segmentId());
            sourceEdges = repository.listSourceLineageByNew(documentId, segment.segmentId()).stream()
                    .map(e -> new ApiDtos.LineageEdgeView(e.newSegmentId(), e.oldSegmentId(), e.ordinal()))
                    .toList();
        }

        List<ApiDtos.LanguageLineageView> languages = new ArrayList<>();
        for (String language : document.targetLanguages()) {
            List<ApiDtos.ReferenceCandidateView> candidates = new ArrayList<>();
            for (String newId : scopeNewIds) {
                List<TranslationLineageRow> edges =
                        repository.listTranslationLineage(documentId, newId, language);
                if (edges.isEmpty()) {
                    continue;
                }
                ReferenceCandidateRow candidate = repository.findReferenceCandidate(documentId, newId, language)
                        .orElseThrow(() -> new IllegalStateException(
                                "译文血缘存在但 REFERENCE 候选缺失: " + newId + "/" + language));
                List<ApiDtos.LineageEdgeView> edgeViews = edges.stream()
                        .map(e -> new ApiDtos.LineageEdgeView(e.newSegmentId(), e.oldSegmentId(), e.ordinal()))
                        .toList();
                candidates.add(new ApiDtos.ReferenceCandidateView(newId, language, candidate.content(),
                        readBoundaries(candidate.fragmentBoundaries()), edgeViews));
            }
            languages.add(new ApiDtos.LanguageLineageView(language, candidates));
        }
        return new ApiDtos.LineageResponse(documentId, sourceEdges, languages);
    }

    private List<ApiDtos.LineageEdgeView> collectAllSourceEdges(long documentId) {
        List<ApiDtos.LineageEdgeView> edges = new ArrayList<>();
        for (SegmentRow segment : repository.listAllSegments(documentId)) {
            for (SourceLineageRow row : repository.listSourceLineageByNew(documentId, segment.segmentId())) {
                edges.add(new ApiDtos.LineageEdgeView(row.newSegmentId(), row.oldSegmentId(), row.ordinal()));
            }
        }
        return edges;
    }

    private static List<NewSegmentInput> validateNewSegments(List<NewSegmentInput> inputs) {
        Set<String> seen = new LinkedHashSet<>();
        for (NewSegmentInput input : inputs) {
            if (!seen.add(input.newSegmentId())) {
                throw ApiException.unprocessable("新段键重复: " + input.newSegmentId());
            }
        }
        return inputs;
    }

    /** 依据旧段数与新段数判定操作类型：1→2~5 拆分，2~5→1 合并，其余 422，不允许混合。 */
    private static String resolveChangeType(int oldCount, int newCount) {
        if (oldCount == 1 && newCount >= 2 && newCount <= MAX_CHANGE_SIZE) {
            return "SPLIT";
        }
        if (newCount == 1 && oldCount >= 2 && oldCount <= MAX_CHANGE_SIZE) {
            return "MERGE";
        }
        throw ApiException.unprocessable("结构修订必须是一段拆成 2~5 段，或 2~5 个连续段合为一段，"
                + "且不能混合两种操作：旧段数 " + oldCount + "，新段数 " + newCount);
    }

    /** 旧段必须全部为当前段、源文版本匹配；合并时还须在当前结构中连续。 */
    private List<SegmentRow> validateOldSegments(long documentId, List<OldSegmentVersion> expected,
                                                 Map<String, SegmentRow> currentById, String changeType) {
        List<SegmentRow> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (OldSegmentVersion version : expected) {
            if (!seen.add(version.segmentId())) {
                throw ApiException.unprocessable("旧段重复: " + version.segmentId());
            }
            SegmentRow row = currentById.get(version.segmentId());
            if (row == null) {
                if (repository.findAnySegment(documentId, version.segmentId()).isPresent()) {
                    throw ApiException.unprocessable("旧段已被结构修订取代，不能再次参与: " + version.segmentId());
                }
                throw ApiException.notFound("旧段不存在: " + version.segmentId());
            }
            if (row.sourceVersion() != version.sourceVersion()) {
                throw ApiException.conflict("旧源段版本冲突: " + version.segmentId() + " 当前版本 "
                        + row.sourceVersion() + "，与提交的 " + version.sourceVersion() + " 不一致");
            }
            rows.add(row);
        }
        if ("MERGE".equals(changeType) && !isConsecutive(rows)) {
            throw ApiException.unprocessable("合并输入的旧段必须在当前结构中连续，且按结构顺序提交");
        }
        return rows;
    }

    private static boolean isConsecutive(List<SegmentRow> rows) {
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).position() != rows.get(i - 1).position() + 1) {
                return false;
            }
        }
        return true;
    }

    /** 新段键全局唯一：不得与任何文档中已有段键（含已淘汰段）重复。 */
    private void validateNewKeys(List<String> newIds, List<String> oldIds) {
        for (String newId : newIds) {
            if (oldIds.contains(newId)) {
                throw ApiException.unprocessable("新段键不能与旧段键相同: " + newId);
            }
            if (repository.segmentKeyExistsGlobally(newId)) {
                throw ApiException.conflict("新段键已存在: " + newId);
            }
        }
    }

    /** 语言集合必须与文档目标语言完全一致（不缺不重不越界），各语言术语版本须等于当前术语版本。 */
    private Map<String, Integer> validateLanguageTermVersions(
            DocumentRow document, List<LanguageTermVersion> inputs) {
        Map<String, Integer> byLanguage = new LinkedHashMap<>();
        for (LanguageTermVersion input : inputs) {
            String language = normalizeLanguage(input.language());
            if (byLanguage.put(language, input.termVersion()) != null) {
                throw ApiException.unprocessable("语言术语版本重复提交: " + language);
            }
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
            }
            if (input.termVersion() != document.termVersion()) {
                throw ApiException.conflict("语言 " + language + " 的术语版本冲突：当前术语版本 "
                        + document.termVersion() + "，与提交的 " + input.termVersion() + " 不一致");
            }
        }
        if (!byLanguage.keySet().equals(new LinkedHashSet<>(document.targetLanguages()))) {
            throw ApiException.unprocessable("语言集合不完整：须覆盖文档全部目标语言 " + document.targetLanguages());
        }
        return byLanguage;
    }

    /**
     * 映射校验：语言集合完整且不重复；每个语言的新段映射恰好覆盖全部新段（无缺漏/重复/越界），
     * 且每段有序旧段来源与源段血缘一致：拆分时只能指向唯一旧段，合并时须按结构顺序覆盖全部旧段。
     */
    private static void validateMappings(List<LanguageMapping> mappings,
                                         List<String> targetLanguages, List<String> newIds,
                                         List<String> oldIds) {
        Set<String> mappedLanguages = new LinkedHashSet<>();
        for (LanguageMapping mapping : mappings) {
            String language = normalizeLanguage(mapping.language());
            if (!mappedLanguages.add(language)) {
                throw ApiException.unprocessable("语言映射重复提交: " + language);
            }
            if (!targetLanguages.contains(language)) {
                throw ApiException.unprocessable("映射语言不在文档目标语言中: " + language);
            }
            Set<String> covered = new LinkedHashSet<>();
            for (ApiDtos.NewSegmentMapping segmentMapping : mapping.mappings()) {
                String newId = segmentMapping.newSegmentId();
                if (!newIds.contains(newId)) {
                    throw ApiException.unprocessable("映射指向未知新段: " + newId + "/" + language);
                }
                if (!covered.add(newId)) {
                    throw ApiException.unprocessable("映射重复: 新段 " + newId + "/" + language + " 提交多次");
                }
                List<String> sources = segmentMapping.oldSegmentIds();
                Set<String> distinctSources = new LinkedHashSet<>(sources);
                if (distinctSources.size() != sources.size()) {
                    throw ApiException.unprocessable("映射旧段重复: " + newId + "/" + language);
                }
                List<String> expected = oldIds.size() == 1 ? List.of(oldIds.get(0)) : oldIds;
                if (!sources.equals(expected)) {
                    throw ApiException.unprocessable("映射缺漏或顺序不一致: 新段 " + newId + "/" + language
                            + " 的有序来源应为 " + expected + "，实际为 " + sources);
                }
            }
            if (!covered.equals(new LinkedHashSet<>(newIds))) {
                throw ApiException.unprocessable("映射缺漏：语言 " + language + " 未覆盖全部新段 " + newIds);
            }
        }
        if (!mappedLanguages.equals(new LinkedHashSet<>(targetLanguages))) {
            throw ApiException.unprocessable("语言集合不完整：映射须覆盖文档全部目标语言 " + targetLanguages);
        }
    }

    private String writeBoundaries(List<Integer> boundaries) {
        try {
            return objectMapper.writeValueAsString(boundaries);
        } catch (Exception e) {
            throw new IllegalStateException("片段边界序列化失败", e);
        }
    }

    private List<Integer> readBoundaries(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Integer.class));
        } catch (Exception e) {
            throw new IllegalStateException("片段边界反序列化失败: " + json, e);
        }
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }
}
