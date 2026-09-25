package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.api.BatchApprovalException;
import com.example.starter.translation.domain.Rows.ApprovalBatchItemRow;
import com.example.starter.translation.domain.Rows.ApprovalBatchRow;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 批量审核：一个事务内重读全部译文最新状态，逐条校验待审核、版本匹配且审核人非作者；
     * 任一条不满足则整批 422 并逐条给出原因，不批准任何一条。全部通过时原子批准为既有
     * APPROVED 状态（与逐条批准共享 approval 状态机），并写入不可变批量审核记录。
     * 不改变文档 draftVersion；expectedDraftVersion 仅固化进批量记录，不作为整批前置条件。
     */
    @Transactional
    public ApiDtos.BatchApprovalResponse approveBatch(long documentId, String actorId,
                                                      ApiDtos.BatchApproveRequest request) {
        List<ApiDtos.BatchApprovalItemInput> items = request.items().stream()
                .map(item -> new ApiDtos.BatchApprovalItemInput(item.segmentId(),
                        normalizeLanguage(item.language()), item.expectedTranslationVersion()))
                .toList();
        Set<String> seen = new HashSet<>();
        for (ApiDtos.BatchApprovalItemInput item : items) {
            if (!seen.add(item.segmentId() + "\n" + item.language())) {
                throw ApiException.badRequest(
                        "同一批次内译文标识重复: " + item.segmentId() + "/" + item.language());
            }
        }
        lockDocument(documentId);
        List<ApiDtos.BatchItemFailure> failures = new ArrayList<>();
        List<TranslationRow> approved = new ArrayList<>();
        for (ApiDtos.BatchApprovalItemInput item : items) {
            SegmentRow segment = repository.findSegment(documentId, item.segmentId()).orElse(null);
            TranslationRow translation = segment == null ? null
                    : repository.findTranslation(documentId, item.segmentId(), item.language()).orElse(null);
            if (translation == null) {
                failures.add(new ApiDtos.BatchItemFailure(item.segmentId(), item.language(),
                        "译文不存在: " + item.segmentId() + "/" + item.language()));
                continue;
            }
            if (translation.author().equals(actorId)) {
                failures.add(new ApiDtos.BatchItemFailure(item.segmentId(), item.language(),
                        "审核人不得是该译文作者"));
                continue;
            }
            if (translation.translationVersion() != item.expectedTranslationVersion()) {
                failures.add(new ApiDtos.BatchItemFailure(item.segmentId(), item.language(),
                        "批准所针对的译文版本 " + item.expectedTranslationVersion()
                                + " 与当前译文版本 " + translation.translationVersion() + " 不匹配"));
                continue;
            }
            if (translation.sourceVersion() != segment.sourceVersion()) {
                failures.add(new ApiDtos.BatchItemFailure(item.segmentId(), item.language(),
                        "译文基于源文版本 " + translation.sourceVersion() + "，当前源文版本 "
                                + segment.sourceVersion() + "，译文待更新，不能批准"));
                continue;
            }
            boolean alreadyApproved = repository.findApproval(documentId, item.segmentId(), item.language())
                    .map(a -> a.translationVersion() == translation.translationVersion())
                    .orElse(false);
            if (alreadyApproved) {
                failures.add(new ApiDtos.BatchItemFailure(item.segmentId(), item.language(),
                        "译文已批准，非待审核状态"));
                continue;
            }
            approved.add(translation);
        }
        if (!failures.isEmpty()) {
            throw new BatchApprovalException(failures);
        }
        LocalDateTime approvedAt = LocalDateTime.now();
        for (TranslationRow translation : approved) {
            repository.upsertApproval(documentId, new ApprovalRow(translation.segmentId(),
                    translation.language(), actorId, translation.sourceVersion(),
                    translation.translationVersion()));
        }
        repository.insertApprovalBatch(new ApprovalBatchRow(request.batchKey(), documentId,
                request.expectedDraftVersion(), actorId, items.size(), approvedAt));
        for (TranslationRow translation : approved) {
            repository.insertApprovalBatchItem(documentId, new ApprovalBatchItemRow(request.batchKey(),
                    translation.segmentId(), translation.language(), translation.translationVersion()));
        }
        List<ApiDtos.BatchApprovalItemResponse> itemResponses = approved.stream()
                .map(t -> new ApiDtos.BatchApprovalItemResponse(t.segmentId(), t.language(),
                        t.translationVersion()))
                .sorted(Comparator.comparing(ApiDtos.BatchApprovalItemResponse::segmentId)
                        .thenComparing(ApiDtos.BatchApprovalItemResponse::language))
                .toList();
        return new ApiDtos.BatchApprovalResponse(request.batchKey(), documentId,
                request.expectedDraftVersion(), actorId, formatApprovedAt(approvedAt), itemResponses);
    }

    /** 查询批量审核记录及该批次的译文批准明细；明细按段落与语言稳定排序。 */
    @Transactional(readOnly = true)
    public ApiDtos.BatchApprovalResponse getApprovalBatch(long documentId, String batchKey) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        ApprovalBatchRow batch = repository.findApprovalBatch(batchKey)
                .filter(b -> b.documentId() == documentId)
                .orElseThrow(() -> ApiException.notFound("批量审核记录不存在: " + batchKey));
        List<ApiDtos.BatchApprovalItemResponse> items = repository.listApprovalBatchItems(batchKey).stream()
                .map(i -> new ApiDtos.BatchApprovalItemResponse(i.segmentId(), i.language(),
                        i.translationVersion()))
                .toList();
        return new ApiDtos.BatchApprovalResponse(batch.batchKey(), batch.documentId(), batch.draftVersion(),
                batch.reviewer(), formatApprovedAt(batch.approvedAt()), items);
    }

    /** 查询文档的全部批量审核记录摘要，按批准时刻与批次键稳定排序。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.BatchApprovalSummary> listApprovalBatches(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return repository.listApprovalBatches(documentId).stream()
                .map(b -> new ApiDtos.BatchApprovalSummary(b.batchKey(), b.documentId(), b.draftVersion(),
                        b.reviewer(), formatApprovedAt(b.approvedAt()), b.itemCount()))
                .toList();
    }

    private static String formatApprovedAt(LocalDateTime approvedAt) {
        return approvedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
