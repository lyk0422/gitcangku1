package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiDtos.LineageFragmentView;
import com.example.starter.translation.api.ApiDtos.ReferenceView;
import com.example.starter.translation.api.ApiDtos.SourceLineageEdgeView;
import com.example.starter.translation.api.ApiDtos.StructureChangeRequest;
import com.example.starter.translation.api.ApiDtos.StructureChangeResponse;
import com.example.starter.translation.api.ApiDtos.StructureFragmentInput;
import com.example.starter.translation.api.ApiDtos.StructureMappingInput;
import com.example.starter.translation.api.ApiDtos.StructureMergeInput;
import com.example.starter.translation.api.ApiDtos.StructureSegmentInput;
import com.example.starter.translation.api.ApiDtos.StructureSegmentView;
import com.example.starter.translation.api.ApiDtos.StructureSplitInput;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.LineageFragmentRow;
import com.example.starter.translation.domain.Rows.SegmentLineageRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.StructureChangeRow;
import com.example.starter.translation.domain.Rows.TranslationReferenceRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
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
 * 结构修订事务服务。
 * 一次 changeKey 把一个当前源段拆成 2~5 段（SPLIT），或把 2~5 个连续当前段合为一段（MERGE），
 * 并为每个目标语言保存新段到旧译文片段的有序来源映射。全部写入与调用方幂等记录在同一事务内提交，
 * 任一校验失败整体回滚，不产生部分新段、不占用 changeKey。
 */
@Service
public class StructureChangeService {

    static final String SPLIT = "SPLIT";
    static final String MERGE = "MERGE";

    private final TranslationRepository repository;

    public StructureChangeService(TranslationRepository repository) {
        this.repository = repository;
    }

    /**
     * 执行结构修订：版本不符或 changeKey 重复返回 409；结构、映射不完整/缺漏/重复返回 422。
     * 成功后原子生成新文档版本，旧段 SUPERSEDED，保存源段与各语言双向血缘，并按映射拼接 REFERENCE 候选。
     */
    @Transactional
    public StructureChangeResponse changeStructure(long documentId, StructureChangeRequest request) {
        DocumentRow document = repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        // 拿到文档锁后先复查幂等记录：并发下同 requestId 的后到事务在此重放先到者结果，
        // 由执行器重新校验请求摘要（异参仍返回 409）。
        if (repository.findRequestLog(request.requestId()).isPresent()) {
            throw new WriteResult.ReplaySignal(request.requestId(), null);
        }
        if (document.draftVersion() != request.expectedDocumentVersion()) {
            throw ApiException.conflict("版本冲突：当前文档版本 " + document.draftVersion()
                    + "，与期望的 " + request.expectedDocumentVersion() + " 不一致");
        }
        if (document.termVersion() != request.expectedTermVersion()) {
            throw ApiException.conflict("术语版本冲突：当前术语版本 " + document.termVersion()
                    + "，与期望的 " + request.expectedTermVersion() + " 不一致");
        }
        if (repository.structureChangeKeyUsed(request.changeKey())) {
            // 并发下同 requestId 同参的后到事务：先到者已提交幂等记录，交回执行器重放原结果；
            // 其他场景（含不同 requestId 或跨文档）复用 changeKey 均为 409。
            if (repository.findRequestLog(request.requestId()).isPresent()) {
                throw new WriteResult.ReplaySignal(request.requestId(), null);
            }
            throw ApiException.conflict("changeKey 已使用: " + request.changeKey());
        }

        String operation = normalizeOperation(request.operation());
        List<SegmentRow> currentSegments = repository.listSegments(documentId);
        Map<String, SegmentRow> currentById = new LinkedHashMap<>();
        for (SegmentRow segment : currentSegments) {
            currentById.put(segment.segmentId(), segment);
        }

        List<String> oldSegmentIds;
        Map<String, Integer> expectedSourceVersions;
        List<StructureSegmentInput> newSegmentInputs;
        if (SPLIT.equals(operation)) {
            if (request.merge() != null) {
                throw ApiException.unprocessable("一次结构修订不能同时携带 split 与 merge");
            }
            StructureSplitInput split = requireBody(request.split(), "split");
            oldSegmentIds = List.of(split.segmentId());
            expectedSourceVersions = Map.of(split.segmentId(), split.expectedSourceVersion());
            newSegmentInputs = split.newSegments();
        } else {
            if (request.split() != null) {
                throw ApiException.unprocessable("一次结构修订不能同时携带 split 与 merge");
            }
            StructureMergeInput merge = requireBody(request.merge(), "merge");
            oldSegmentIds = merge.segmentIds();
            if (merge.expectedSourceVersions().size() != oldSegmentIds.size()) {
                throw ApiException.unprocessable("expectedSourceVersions 数量与合并段数不一致");
            }
            expectedSourceVersions = new LinkedHashMap<>();
            for (int i = 0; i < oldSegmentIds.size(); i++) {
                if (expectedSourceVersions.put(oldSegmentIds.get(i), merge.expectedSourceVersions().get(i)) != null) {
                    throw ApiException.unprocessable("合并输入存在重复段: " + oldSegmentIds.get(i));
                }
            }
            newSegmentInputs = List.of(merge.newSegment());
        }

        validateOldSegments(oldSegmentIds, expectedSourceVersions, currentSegments, currentById);
        validateNewSegmentKeys(documentId, newSegmentInputs, currentById);

        Map<String, TranslationRow> oldTranslations = new LinkedHashMap<>();
        for (String oldSegmentId : oldSegmentIds) {
            for (String language : document.targetLanguages()) {
                repository.findTranslation(documentId, oldSegmentId, language)
                        .ifPresent(t -> oldTranslations.put(key(oldSegmentId, language), t));
            }
        }
        List<Mapping> mappings = validateMappings(request.mappings(), document.targetLanguages(),
                oldSegmentIds, newSegmentInputs, oldTranslations);

        // 校验全部通过后才开始写入：先落结构修订主记录（changeKey 唯一约束兜底）
        int documentVersion = document.draftVersion() + 1;
        try {
            repository.insertStructureChange(documentId, new StructureChangeRow(
                    request.changeKey(), operation, documentVersion, request.expectedDocumentVersion()));
        } catch (DuplicateKeyException e) {
            if (repository.findRequestLog(request.requestId()).isPresent()) {
                throw new WriteResult.ReplaySignal(request.requestId(), e);
            }
            throw ApiException.conflict("changeKey 已使用: " + request.changeKey());
        }

        // 源段血缘 + 插入新段
        for (int i = 0; i < oldSegmentIds.size(); i++) {
            String oldSegmentId = oldSegmentIds.get(i);
            int oldSourceVersion = expectedSourceVersions.get(oldSegmentId);
            for (int j = 0; j < newSegmentInputs.size(); j++) {
                int ordinal = SPLIT.equals(operation) ? j : i;
                repository.insertSegmentLineage(documentId, new SegmentLineageRow(
                        request.changeKey(), oldSegmentId, oldSourceVersion,
                        newSegmentInputs.get(j).segmentId(), ordinal));
            }
        }
        for (String oldSegmentId : oldSegmentIds) {
            repository.markSegmentSuperseded(documentId, oldSegmentId);
        }
        List<String> newOrder = buildNewOrder(currentSegments, oldSegmentIds,
                newSegmentInputs.stream().map(StructureSegmentInput::segmentId).toList());
        for (int i = 0; i < newSegmentInputs.size(); i++) {
            StructureSegmentInput input = newSegmentInputs.get(i);
            repository.insertStructureSegment(documentId, input.segmentId(), input.sourceText(),
                    newOrder.indexOf(input.segmentId()));
        }
        repository.resequencePositions(documentId, newOrder);

        // 各语言双向译文血缘 + 拼接 REFERENCE 候选
        List<ReferenceView> references = new ArrayList<>();
        for (Mapping mapping : mappings) {
            StringBuilder content = new StringBuilder();
            List<LineageFragmentView> fragmentViews = new ArrayList<>();
            for (int ordinal = 0; ordinal < mapping.fragments().size(); ordinal++) {
                StructureFragmentInput fragmentInput = mapping.fragments().get(ordinal);
                TranslationRow oldTranslation = oldTranslations.get(
                        key(fragmentInput.segmentId(), mapping.language()));
                int start = content.length();
                content.append(oldTranslation.content());
                int end = content.length();
                repository.insertLineageFragment(documentId, new LineageFragmentRow(
                        request.changeKey(), mapping.newSegmentId(), mapping.language(),
                        fragmentInput.segmentId(), oldTranslation.translationVersion(),
                        ordinal, start, end));
                fragmentViews.add(new LineageFragmentView(fragmentInput.segmentId(),
                        oldTranslation.translationVersion(), ordinal, start, end));
            }
            String referenceContent = content.toString();
            repository.insertTranslationReference(documentId, new TranslationReferenceRow(
                    request.changeKey(), mapping.newSegmentId(), mapping.language(), referenceContent));
            references.add(new ReferenceView(request.changeKey(), mapping.newSegmentId(),
                    mapping.language(), referenceContent, fragmentViews));
        }

        repository.updateDraftVersion(documentId, documentVersion);

        List<StructureSegmentView> segmentViews = newSegmentInputs.stream()
                .map(input -> new StructureSegmentView(input.segmentId(), input.sourceText(), 1,
                        newOrder.indexOf(input.segmentId())))
                .toList();
        return new StructureChangeResponse(documentId, request.changeKey(), operation,
                documentVersion, segmentViews, references);
    }

    /** 查询当前结构与文档版本，只读。 */
    @Transactional(readOnly = true)
    public ApiDtos.CurrentStructureResponse getCurrentStructure(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<StructureSegmentView> segments = repository.listSegments(documentId).stream()
                .map(s -> new StructureSegmentView(s.segmentId(), s.sourceText(), s.sourceVersion(), s.position()))
                .toList();
        return new ApiDtos.CurrentStructureResponse(documentId, document.draftVersion(), segments);
    }

    /**
     * 查询指定段的跨语言血缘，只读：作为新段时返回旧源段来源与各语言 REFERENCE 候选；
     * 作为旧段时返回派生出的新段及消费该旧译文的有序片段。段不存在（含历史段之外的 ID）返回 404。
     */
    @Transactional(readOnly = true)
    public ApiDtos.LineageResponse getLineage(long documentId, String segmentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        SegmentRow segment = repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));

        List<SourceLineageEdgeView> sources = repository.listLineageByNewSegment(documentId, segmentId).stream()
                .map(row -> new SourceLineageEdgeView(row.changeKey(), row.oldSegmentId(),
                        row.oldSourceVersion(), row.ordinal()))
                .toList();
        List<SourceLineageEdgeView> derived = repository.listLineageByOldSegment(documentId, segmentId).stream()
                .map(row -> new SourceLineageEdgeView(row.changeKey(), row.newSegmentId(),
                        row.oldSourceVersion(), row.ordinal()))
                .toList();

        List<ReferenceView> references = toReferenceViews(
                repository.listFragmentsByNewSegment(documentId, segmentId),
                repository.listReferences(documentId, List.of(segmentId)));
        List<LineageFragmentRow> derivedFragments = repository.listFragmentsByOldSegment(documentId, segmentId);
        List<String> derivedNewSegmentIds = derivedFragments.stream()
                .map(LineageFragmentRow::newSegmentId).distinct().toList();
        List<ReferenceView> derivedTranslations = toReferenceViews(derivedFragments,
                repository.listReferences(documentId, derivedNewSegmentIds));

        return new ApiDtos.LineageResponse(documentId, segmentId, segment.current(),
                sources, derived, references, derivedTranslations);
    }

    private List<ReferenceView> toReferenceViews(List<LineageFragmentRow> fragments,
                                                 List<TranslationReferenceRow> references) {
        Map<String, TranslationReferenceRow> referenceByKey = new LinkedHashMap<>();
        for (TranslationReferenceRow reference : references) {
            referenceByKey.put(key(reference.newSegmentId(), reference.language()), reference);
        }
        Map<String, List<LineageFragmentRow>> fragmentsByKey = new LinkedHashMap<>();
        for (LineageFragmentRow fragment : fragments) {
            fragmentsByKey.computeIfAbsent(key(fragment.newSegmentId(), fragment.language()), k -> new ArrayList<>())
                    .add(fragment);
        }
        List<ReferenceView> views = new ArrayList<>();
        for (Map.Entry<String, List<LineageFragmentRow>> entry : fragmentsByKey.entrySet()) {
            TranslationReferenceRow reference = referenceByKey.get(entry.getKey());
            if (reference == null) {
                continue;
            }
            List<LineageFragmentView> fragmentViews = entry.getValue().stream()
                    .map(f -> new LineageFragmentView(f.oldSegmentId(), f.oldTranslationVersion(),
                            f.ordinal(), f.startOffset(), f.endOffset()))
                    .toList();
            views.add(new ReferenceView(reference.changeKey(), reference.newSegmentId(),
                    reference.language(), reference.content(), fragmentViews));
        }
        return views;
    }

    private void validateOldSegments(List<String> oldSegmentIds, Map<String, Integer> expectedSourceVersions,
                                     List<SegmentRow> currentSegments, Map<String, SegmentRow> currentById) {
        Set<String> seen = new HashSet<>();
        for (String oldSegmentId : oldSegmentIds) {
            if (!seen.add(oldSegmentId)) {
                throw ApiException.unprocessable("结构修订输入存在重复段: " + oldSegmentId);
            }
            SegmentRow segment = currentById.get(oldSegmentId);
            if (segment == null) {
                throw ApiException.unprocessable("旧段不存在或不是当前段: " + oldSegmentId);
            }
            Integer expected = expectedSourceVersions.get(oldSegmentId);
            if (expected == null || expected != segment.sourceVersion()) {
                throw ApiException.conflict("旧源段版本冲突: " + oldSegmentId + " 当前版本 "
                        + segment.sourceVersion() + "，与期望的 " + expected + " 不一致");
            }
        }
        if (oldSegmentIds.size() > 1) {
            // 合并要求旧段在当前结构中连续
            List<Integer> positions = oldSegmentIds.stream()
                    .map(id -> currentById.get(id).position()).sorted().toList();
            for (int i = 1; i < positions.size(); i++) {
                if (positions.get(i) != positions.get(i - 1) + 1) {
                    throw ApiException.unprocessable("合并输入的段在当前结构中不连续");
                }
            }
            List<String> inPositionOrder = currentSegments.stream()
                    .filter(s -> oldSegmentIds.contains(s.segmentId()))
                    .map(SegmentRow::segmentId).toList();
            if (!inPositionOrder.equals(oldSegmentIds)) {
                throw ApiException.unprocessable("合并输入的段必须按当前结构顺序排列");
            }
        }
    }

    private void validateNewSegmentKeys(long documentId, List<StructureSegmentInput> newSegmentInputs,
                                        Map<String, SegmentRow> currentById) {
        Set<String> seen = new HashSet<>();
        for (StructureSegmentInput input : newSegmentInputs) {
            if (!seen.add(input.segmentId())) {
                throw ApiException.unprocessable("新段键重复: " + input.segmentId());
            }
            if (currentById.containsKey(input.segmentId())) {
                throw ApiException.conflict("新段键与当前段冲突: " + input.segmentId());
            }
            // 新段键全局唯一：不得复用任何历史（含已废止）段键
            if (repository.findSegment(documentId, input.segmentId()).isPresent()) {
                throw ApiException.conflict("新段键已被历史段占用: " + input.segmentId());
            }
        }
    }

    /**
     * 映射校验：语言集合必须与文档目标语言完全一致；每个新段在每种语言恰有一条映射；
     * 每条映射的片段只能引用本次旧段、不能缺漏重复；旧译文版本必须等于当前译文版本，
     * 且引用全部必须有实际旧译文（结构修订只迁移已有译文）。
     */
    private List<Mapping> validateMappings(List<StructureMappingInput> mappings, List<String> targetLanguages,
                                           List<String> oldSegmentIds,
                                           List<StructureSegmentInput> newSegmentInputs,
                                           Map<String, TranslationRow> oldTranslations) {
        Set<String> expectedKeys = new HashSet<>();
        for (StructureSegmentInput input : newSegmentInputs) {
            for (String language : targetLanguages) {
                expectedKeys.add(key(input.segmentId(), language));
            }
        }
        Set<String> seenKeys = new HashSet<>();
        List<Mapping> parsed = new ArrayList<>();
        for (StructureMappingInput input : mappings) {
            String language = normalizeLanguage(input.language());
            if (!targetLanguages.contains(language)) {
                throw ApiException.unprocessable("映射语言不在文档目标语言中: " + language);
            }
            if (!newSegmentInputs.stream().anyMatch(s -> s.segmentId().equals(input.newSegmentId()))) {
                throw ApiException.unprocessable("映射引用了不属于本次修订的新段: " + input.newSegmentId());
            }
            String mappingKey = key(input.newSegmentId(), language);
            if (!seenKeys.add(mappingKey)) {
                throw ApiException.unprocessable("映射重复: " + mappingKey);
            }
            Set<String> fragmentSegments = new HashSet<>();
            for (StructureFragmentInput fragment : input.fragments()) {
                if (!oldSegmentIds.contains(fragment.segmentId())) {
                    throw ApiException.unprocessable("片段引用了不属于本次修订的旧段: " + fragment.segmentId());
                }
                if (!fragmentSegments.add(fragment.segmentId())) {
                    throw ApiException.unprocessable("片段重复引用旧段: " + fragment.segmentId()
                            + "（新段 " + input.newSegmentId() + "/" + language + "）");
                }
                TranslationRow oldTranslation = oldTranslations.get(key(fragment.segmentId(), language));
                if (oldTranslation == null) {
                    throw ApiException.unprocessable("旧段在该语言尚无译文，映射缺漏: "
                            + fragment.segmentId() + "/" + language);
                }
                if (oldTranslation.translationVersion() != fragment.translationVersion()) {
                    throw ApiException.conflict("旧译文版本冲突: " + fragment.segmentId() + "/" + language
                            + " 当前译文版本 " + oldTranslation.translationVersion()
                            + "，与期望的 " + fragment.translationVersion() + " 不一致");
                }
            }
            parsed.add(new Mapping(input.newSegmentId(), language, input.fragments()));
        }
        if (!seenKeys.equals(expectedKeys)) {
            Set<String> missing = new HashSet<>(expectedKeys);
            missing.removeAll(seenKeys);
            Set<String> extra = new HashSet<>(seenKeys);
            extra.removeAll(expectedKeys);
            throw ApiException.unprocessable("语言/新段映射集合不完整，缺失 " + missing + "，多余 " + extra);
        }

        // 每个旧段在每种语言必须被同操作语义下的映射完整消费一次：
        // SPLIT：唯一旧段的译文必须原样迁移到每个新段（各新段恰好 1 个片段）；
        // MERGE：每个新段（唯一）的每语言映射必须按顺序包含全部旧段且各一次。
        for (Mapping mapping : parsed) {
            List<String> referenced = mapping.fragments().stream()
                    .map(StructureFragmentInput::segmentId).toList();
            if (oldSegmentIds.size() == 1) {
                if (referenced.size() != 1 || !referenced.get(0).equals(oldSegmentIds.get(0))) {
                    throw ApiException.unprocessable("拆分映射每个新段每语言只能引用唯一旧段一次: "
                            + mapping.newSegmentId() + "/" + mapping.language());
                }
            } else {
                if (!referenced.equals(oldSegmentIds)) {
                    throw ApiException.unprocessable("合并映射必须按旧段顺序包含全部旧段且不重不漏: "
                            + mapping.newSegmentId() + "/" + mapping.language());
                }
            }
        }
        return parsed;
    }

    private List<String> buildNewOrder(List<SegmentRow> currentSegments, List<String> oldSegmentIds,
                                       List<String> newSegmentIds) {
        List<String> order = new ArrayList<>();
        boolean inserted = false;
        for (SegmentRow segment : currentSegments) {
            if (oldSegmentIds.contains(segment.segmentId())) {
                if (!inserted) {
                    order.addAll(newSegmentIds);
                    inserted = true;
                }
            } else {
                order.add(segment.segmentId());
            }
        }
        return order;
    }

    private static <T> T requireBody(T body, String name) {
        if (body == null) {
            throw ApiException.unprocessable(name + " 请求体不能为空");
        }
        return body;
    }

    private static String normalizeOperation(String operation) {
        String normalized = operation.trim().toUpperCase(Locale.ROOT);
        if (!SPLIT.equals(normalized) && !MERGE.equals(normalized)) {
            throw ApiException.unprocessable("operation 只能是 SPLIT 或 MERGE: " + operation);
        }
        return normalized;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    /** 解析后的一条新段×语言映射。 */
    private record Mapping(String newSegmentId, String language, List<StructureFragmentInput> fragments) {
    }
}
