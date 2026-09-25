package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.api.BatchApprovalValidationException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.BatchApprovalItemRow;
import com.example.starter.translation.domain.Rows.BatchApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 多语种段落修订与发布快照的核心业务服务。
 * 所有写操作先对文档行加 FOR UPDATE 行锁，保证同一文档的修改、审核与发布串行，
 * 并发下对应一个一致的文档状态；方法均加入调用方事务，与幂等记录原子提交。
 */
@Service
public class TranslationService {

    private final TranslationRepository repository;
    private final ObjectMapper objectMapper;

    public TranslationService(TranslationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 建文档：1~5 种目标语言，可携带初始段落，初始草稿版本 1、发布版本 0。 */
    @Transactional
    public ApiDtos.DocumentResponse createDocument(ApiDtos.CreateDocumentRequest request) {
        List<String> languages = normalizeLanguages(request.targetLanguages());
        List<ApiDtos.SegmentInput> segments = request.segments();
        long distinct = segments.stream().map(ApiDtos.SegmentInput::segmentId).distinct().count();
        if (distinct != segments.size()) {
            throw ApiException.unprocessable("初始段落 segmentId 重复");
        }
        long documentId = repository.insertDocument(languages);
        for (ApiDtos.SegmentInput segment : segments) {
            repository.insertSegment(documentId, segment.segmentId(), segment.sourceText());
        }
        return new ApiDtos.DocumentResponse(documentId, 1, 0, languages);
    }

    /** 增加段落：segmentId 文档内唯一，草稿版本加一。 */
    @Transactional
    public ApiDtos.SegmentResponse addSegment(long documentId, ApiDtos.AddSegmentRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (repository.findSegment(documentId, request.segmentId()).isPresent()) {
            throw ApiException.conflict("段落已存在: " + request.segmentId());
        }
        repository.insertSegment(documentId, request.segmentId(), request.sourceText());
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, request.segmentId(), 1, draftVersion);
    }

    /** 源文修订：源文版本加一、草稿版本加一；相关译文因源文版本落后而待更新。 */
    @Transactional
    public ApiDtos.SegmentResponse reviseSource(long documentId, String segmentId,
                                              ApiDtos.ReviseSourceRequest request) {
        DocumentRow document = lockDocument(documentId);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        int sourceVersion = segment.sourceVersion() + 1;
        repository.updateSegmentSource(documentId, segmentId, request.sourceText(), sourceVersion);
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.SegmentResponse(documentId, segmentId, sourceVersion, draftVersion);
    }

    /** 译文提交：所依据源文版本必须等于当前源文版本；译文版本递增，草稿版本加一。 */
    @Transactional
    public ApiDtos.TranslationResponse submitTranslation(long documentId, String segmentId, String language,
                                                         String actorId, ApiDtos.SubmitTranslationRequest request) {
        DocumentRow document = lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        if (!document.targetLanguages().contains(normalizedLanguage)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + normalizedLanguage);
        }
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        if (request.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文所依据的源文版本 " + request.sourceVersion()
                    + " 与当前源文版本 " + segment.sourceVersion() + " 不匹配");
        }
        int translationVersion = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .map(TranslationRow::translationVersion).orElse(0) + 1;
        repository.upsertTranslation(documentId, new TranslationRow(segmentId, normalizedLanguage,
                request.content(), actorId, segment.sourceVersion(), translationVersion));
        int draftVersion = bumpDraftVersion(document);
        return new ApiDtos.TranslationResponse(documentId, segmentId, normalizedLanguage,
                translationVersion, segment.sourceVersion(), draftVersion);
    }

    /** 译文批准：审核人不得是作者，且必须同时匹配当前源文与译文版本。 */
    @Transactional
    public ApiDtos.ApprovalResponse approveTranslation(long documentId, String segmentId, String language,
                                                       String actorId, ApiDtos.ApproveTranslationRequest request) {
        lockDocument(documentId);
        String normalizedLanguage = normalizeLanguage(language);
        SegmentRow segment = findSegmentOrThrow(documentId, segmentId);
        TranslationRow translation = repository.findTranslation(documentId, segmentId, normalizedLanguage)
                .orElseThrow(() -> ApiException.notFound(
                        "译文不存在: " + segmentId + "/" + normalizedLanguage));
        if (translation.author().equals(actorId)) {
            throw ApiException.unprocessable("审核人不得是该译文作者");
        }
        if (translation.translationVersion() != request.translationVersion()) {
            throw ApiException.unprocessable("批准所针对的译文版本 " + request.translationVersion()
                    + " 与当前译文版本 " + translation.translationVersion() + " 不匹配");
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion() + "，译文待更新，不能批准");
        }
        repository.upsertApproval(documentId, new ApprovalRow(segmentId, normalizedLanguage, actorId,
                segment.sourceVersion(), translation.translationVersion()));
        return new ApiDtos.ApprovalResponse(documentId, segmentId, normalizedLanguage, actorId,
                translation.translationVersion(), segment.sourceVersion());
    }

    /**
     * 发布：校验期望版本（不符 409），再校验全部段落在全部目标语言均有有效批准（缺译或审核失效 422），
     * 全部通过后原子生成完整只读快照并递增发布版本；任何失败回滚，不产生部分快照。
     */
    @Transactional
    public ApiDtos.PublishResponse publish(long documentId, ApiDtos.PublishRequest request) {
        DocumentRow document = lockDocument(documentId);
        if (document.draftVersion() != request.expectedDraftVersion()
                || document.publishedVersion() != request.expectedPublishedVersion()) {
            throw ApiException.conflict("版本冲突：当前草稿版本 " + document.draftVersion()
                    + "、发布版本 " + document.publishedVersion() + "，与期望的 "
                    + request.expectedDraftVersion() + "/" + request.expectedPublishedVersion() + " 不一致");
        }
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                if (translation == null) {
                    throw ApiException.unprocessable(
                            "缺少译文: " + segment.segmentId() + "/" + language);
                }
                if (translation.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("译文待更新: " + segment.segmentId() + "/" + language
                            + " 基于源文版本 " + translation.sourceVersion()
                            + "，当前源文版本 " + segment.sourceVersion());
                }
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                if (approval == null) {
                    throw ApiException.unprocessable(
                            "缺少批准: " + segment.segmentId() + "/" + language);
                }
                if (approval.translationVersion() != translation.translationVersion()
                        || approval.sourceVersion() != segment.sourceVersion()) {
                    throw ApiException.unprocessable("批准已失效: " + segment.segmentId() + "/" + language
                            + "，源文或译文版本已改变");
                }
            }
        }
        int publishedVersion = document.publishedVersion() + 1;
        repository.insertSnapshot(documentId, publishedVersion,
                buildSnapshotJson(document, publishedVersion, segments, translations, approvals));
        repository.updatePublishedVersion(documentId, publishedVersion);
        return new ApiDtos.PublishResponse(documentId, publishedVersion);
    }

    /**
     * 批量审核：在一个事务内锁定文档并重新读取全部译文最新状态，逐条校验
     * （待审核即当前无有效批准、译文版本匹配、审核人不是作者、译文基于当前源文版本）。
     * 任一条不满足则整批 422 回滚、不批准任何一条；全部通过时与单条批准共享同一 approval 状态机，
     * 原子写入批准与不可变批量记录。不改变文档 draftVersion。
     */
    @Transactional
    public ApiDtos.BatchApprovalResponse approveTranslationsBatch(long documentId, String actorId,
                                                                  ApiDtos.BatchApprovalRequest request) {
        DocumentRow document = lockDocument(documentId);

        // 并发同 batchKey：本事务在开事务查重后才拿到文档锁，期间可能已有他事务原子提交同键批次。
        // 持锁后复查，命中则整体转为重放首次快照（由 WriteExecutor 回滚本事务后重放），
        // 避免把他事务已批准的条目误判为“已批准”而返回 422。
        if (repository.findBatchApproval(request.batchKey()).isPresent()) {
            throw new WriteResult.ReplaySignal(request.batchKey(), null);
        }

        // 同一批次内译文标识（段落+语言）不得重复，重复返回 400
        Set<String> seen = new HashSet<>();
        for (ApiDtos.BatchApprovalItemInput item : request.items()) {
            String identity = item.segmentId() + " " + normalizeLanguage(item.language());
            if (!seen.add(identity)) {
                throw ApiException.badRequest(
                        "同一批次内译文标识重复: " + item.segmentId() + "/" + item.language());
            }
        }

        Map<String, SegmentRow> segments = repository.listSegments(documentId).stream()
                .collect(Collectors.toMap(SegmentRow::segmentId, Function.identity()));
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> key(t.segmentId(), t.language()), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> key(a.segmentId(), a.language()), Function.identity()));

        List<ApiDtos.BatchApprovalItemError> errors = new ArrayList<>();
        List<ApiDtos.BatchApprovalItemResponse> responses = new ArrayList<>();
        int index = 0;
        for (ApiDtos.BatchApprovalItemInput item : request.items()) {
            index++;
            String language = normalizeLanguage(item.language());
            String identityKey = key(item.segmentId(), language);

            SegmentRow segment = segments.get(item.segmentId());
            if (segment == null) {
                errors.add(new ApiDtos.BatchApprovalItemError(index, item.segmentId(), language,
                        "段落不存在"));
                continue;
            }
            TranslationRow translation = translations.get(identityKey);
            if (translation == null) {
                errors.add(new ApiDtos.BatchApprovalItemError(index, item.segmentId(), language,
                        "译文不存在"));
                continue;
            }
            ApprovalRow approval = approvals.get(identityKey);
            if (approval != null && approval.translationVersion() == translation.translationVersion()
                    && approval.sourceVersion() == segment.sourceVersion()) {
                errors.add(new ApiDtos.BatchApprovalItemError(index, item.segmentId(), language,
                        "译文已批准，不是待审核状态"));
                continue;
            }
            if (translation.author().equals(actorId)) {
                errors.add(new ApiDtos.BatchApprovalItemError(index, item.segmentId(), language,
                        "审核人不得是该译文作者"));
                continue;
            }
            if (translation.translationVersion() != item.expectedTranslationVersion()) {
                errors.add(new ApiDtos.BatchApprovalItemError(index, item.segmentId(), language,
                        "译文版本 " + item.expectedTranslationVersion() + " 与当前版本 "
                                + translation.translationVersion() + " 不匹配"));
                continue;
            }
            if (translation.sourceVersion() != segment.sourceVersion()) {
                errors.add(new ApiDtos.BatchApprovalItemError(index, item.segmentId(), language,
                        "译文基于源文版本 " + translation.sourceVersion() + "，当前源文版本 "
                                + segment.sourceVersion() + "，译文待更新，不能批准"));
                continue;
            }
            responses.add(new ApiDtos.BatchApprovalItemResponse(item.segmentId(), language, actorId,
                    translation.translationVersion(), segment.sourceVersion()));
        }

        if (!errors.isEmpty()) {
            // 整批拒绝：不写入任何批准与批次记录，事务回滚
            throw new BatchApprovalValidationException(errors);
        }

        // 全部通过：走与单条批准完全相同的 approval upsert 状态机
        int lineNo = 0;
        for (ApiDtos.BatchApprovalItemResponse approved : responses) {
            lineNo++;
            repository.upsertApproval(documentId, new ApprovalRow(approved.segmentId(), approved.language(),
                    actorId, approved.sourceVersion(), approved.translationVersion()));
            repository.insertBatchApprovalItem(new BatchApprovalItemRow(request.batchKey(), lineNo,
                    documentId, approved.segmentId(), approved.language(),
                    approved.translationVersion(), approved.sourceVersion()));
        }
        repository.insertBatchApproval(new BatchApprovalRow(request.batchKey(), documentId,
                request.expectedDraftVersion(), actorId, null));
        // 审核时刻以数据库默认时间戳为准，回查保证响应与不可变记录一致
        BatchApprovalRow persisted = repository.findBatchApproval(request.batchKey())
                .orElseThrow(() -> new IllegalStateException("批量审核记录插入后未回查到: " + request.batchKey()));

        return new ApiDtos.BatchApprovalResponse(persisted.batchKey(), documentId,
                request.expectedDraftVersion(), actorId, persisted.approvedAt(), responses);
    }

    /** 查询不可变批量审核记录及其按批次的译文批准明细，稳定排序；批次不存在返回 404。 */
    @Transactional(readOnly = true)
    public ApiDtos.BatchApprovalRecordResponse getBatchApproval(long documentId, String batchKey) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        BatchApprovalRow row = repository.findBatchApproval(batchKey)
                .orElseThrow(() -> ApiException.notFound("批量审核记录不存在: " + batchKey));
        if (row.documentId() != documentId) {
            throw ApiException.notFound("批量审核记录不属于该文档: " + batchKey);
        }
        List<ApiDtos.BatchApprovalItemResponse> items = repository.listBatchApprovalItems(batchKey).stream()
                .map(item -> new ApiDtos.BatchApprovalItemResponse(item.segmentId(), item.language(),
                        row.reviewer(), item.translationVersion(), item.sourceVersion()))
                .toList();
        return new ApiDtos.BatchApprovalRecordResponse(row.batchKey(), row.documentId(),
                row.expectedDraftVersion(), row.reviewer(), row.approvedAt(), items);
    }

    /** 查询指定发布版本的只读快照 JSON；不存在返回 404。 */
    @Transactional(readOnly = true)
    public String getRelease(long documentId, int publishedVersion) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return repository.findSnapshot(documentId, publishedVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "发布版本不存在: " + documentId + "/" + publishedVersion));
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private SegmentRow findSegmentOrThrow(long documentId, String segmentId) {
        return repository.findSegment(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
    }

    private int bumpDraftVersion(DocumentRow document) {
        int draftVersion = document.draftVersion() + 1;
        repository.updateDraftVersion(document.documentId(), draftVersion);
        return draftVersion;
    }

    private static String key(String segmentId, String language) {
        return segmentId + " " + language;
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeLanguages(List<String> languages) {
        List<String> normalized = languages.stream().map(TranslationService::normalizeLanguage).distinct().toList();
        if (normalized.size() != languages.size()) {
            throw ApiException.unprocessable("目标语言重复");
        }
        return normalized;
    }

    /** 生成完整只读快照 JSON：全部段落源文及各语言译文、作者、审核人与版本号。 */
    private String buildSnapshotJson(DocumentRow document, int publishedVersion, List<SegmentRow> segments,
                                     Map<String, TranslationRow> translations,
                                     Map<String, ApprovalRow> approvals) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.documentId());
        snapshot.put("publishedVersion", publishedVersion);
        snapshot.put("draftVersion", document.draftVersion());
        snapshot.put("targetLanguages", document.targetLanguages());
        List<Map<String, Object>> segmentList = new ArrayList<>();
        for (SegmentRow segment : segments) {
            Map<String, Object> segmentJson = new LinkedHashMap<>();
            segmentJson.put("segmentId", segment.segmentId());
            segmentJson.put("sourceText", segment.sourceText());
            segmentJson.put("sourceVersion", segment.sourceVersion());
            List<Map<String, Object>> translationList = new ArrayList<>();
            for (String language : document.targetLanguages()) {
                TranslationRow translation = translations.get(key(segment.segmentId(), language));
                ApprovalRow approval = approvals.get(key(segment.segmentId(), language));
                Map<String, Object> translationJson = new LinkedHashMap<>();
                translationJson.put("language", language);
                translationJson.put("content", translation.content());
                translationJson.put("author", translation.author());
                translationJson.put("translationVersion", translation.translationVersion());
                translationJson.put("sourceVersion", translation.sourceVersion());
                translationJson.put("reviewer", approval.reviewer());
                translationList.add(translationJson);
            }
            segmentJson.put("translations", translationList);
            segmentList.add(segmentJson);
        }
        snapshot.put("segments", segmentList);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }
}
