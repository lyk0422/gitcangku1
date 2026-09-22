package com.example.starter.translation.service;

import com.example.starter.translation.dto.Dtos.AddSegmentRequest;
import com.example.starter.translation.dto.Dtos.ApprovalView;
import com.example.starter.translation.dto.Dtos.ApproveRequest;
import com.example.starter.translation.dto.Dtos.CreateDocumentRequest;
import com.example.starter.translation.dto.Dtos.DocumentView;
import com.example.starter.translation.dto.Dtos.PublicationView;
import com.example.starter.translation.dto.Dtos.PublishRequest;
import com.example.starter.translation.dto.Dtos.PublishView;
import com.example.starter.translation.dto.Dtos.ReviseSourceRequest;
import com.example.starter.translation.dto.Dtos.SegmentInput;
import com.example.starter.translation.dto.Dtos.SegmentView;
import com.example.starter.translation.dto.Dtos.SnapshotSegmentView;
import com.example.starter.translation.dto.Dtos.SubmitTranslationRequest;
import com.example.starter.translation.dto.Dtos.TranslationView;
import com.example.starter.translation.error.ApiException;
import com.example.starter.translation.repo.DocumentRepository;
import com.example.starter.translation.repo.DocumentRepository.DocumentRow;
import com.example.starter.translation.repo.PublicationRepository;
import com.example.starter.translation.repo.PublicationRepository.SnapshotSegmentRow;
import com.example.starter.translation.repo.PublicationRepository.SnapshotTranslationRow;
import com.example.starter.translation.repo.SegmentRepository;
import com.example.starter.translation.repo.SegmentRepository.SegmentRow;
import com.example.starter.translation.repo.TranslationRepository;
import com.example.starter.translation.repo.TranslationRepository.ApprovalRow;
import com.example.starter.translation.repo.TranslationRepository.TranslationRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 多语种段落修订与发布快照的核心业务服务。
 * 写方法均先对文档行加锁（SELECT ... FOR UPDATE），
 * 使发布与修改、审核等并发写对应一个一致的文档状态。
 */
@Service
public class TranslationService {

    private final DocumentRepository documents;
    private final SegmentRepository segments;
    private final TranslationRepository translations;
    private final PublicationRepository publications;
    private final Clock clock;

    public TranslationService(DocumentRepository documents, SegmentRepository segments,
                              TranslationRepository translations, PublicationRepository publications,
                              Clock clock) {
        this.documents = documents;
        this.segments = segments;
        this.translations = translations;
        this.publications = publications;
        this.clock = clock;
    }

    /**
     * 建文档：创建文档、目标语言（1~5 种）与初始段落；草稿版本从 1 开始，发布版本从 0 开始。
     */
    @Transactional
    public DocumentView createDocument(CreateDocumentRequest req) {
        String documentId = isBlank(req.documentId()) ? UUID.randomUUID().toString() : req.documentId().trim();
        List<String> languages = validateLanguages(req.targetLanguages());
        List<SegmentInput> inputs = validateSegments(req.segments());
        if (documents.findById(documentId).isPresent()) {
            throw ApiException.conflict("文档已存在: " + documentId);
        }
        Instant now = Instant.now(clock);
        try {
            documents.insert(documentId, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("文档已存在: " + documentId);
        }
        for (String language : languages) {
            documents.insertLanguage(documentId, language);
        }
        for (SegmentInput input : inputs) {
            segments.insert(documentId, input.segmentId().trim(), input.sourceText());
        }
        return documentView(documents.findById(documentId).orElseThrow());
    }

    /**
     * 增段落：文档草稿版本加一。
     */
    @Transactional
    public DocumentView addSegment(String documentId, AddSegmentRequest req) {
        DocumentRow doc = lockDocument(documentId);
        if (isBlank(req.segmentId())) {
            throw ApiException.badRequest("segmentId 不能为空");
        }
        if (isBlank(req.sourceText())) {
            throw ApiException.badRequest("sourceText 不能为空");
        }
        String segmentId = req.segmentId().trim();
        if (segments.find(documentId, segmentId).isPresent()) {
            throw ApiException.conflict("段落已存在: " + segmentId);
        }
        segments.insert(documentId, segmentId, req.sourceText());
        bumpDraft(doc);
        return documentView(documents.findById(documentId).orElseThrow());
    }

    /**
     * 源文修订：仅增加该段落源文版本并使相关译文待更新（既有批准随之失效），文档草稿版本加一；
     * 不触碰任何已发布快照。
     */
    @Transactional
    public DocumentView reviseSource(String documentId, String segmentId, ReviseSourceRequest req) {
        DocumentRow doc = lockDocument(documentId);
        SegmentRow segment = segments.find(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        if (isBlank(req.sourceText())) {
            throw ApiException.badRequest("sourceText 不能为空");
        }
        segments.updateSource(documentId, segmentId, req.sourceText(), segment.sourceVersion() + 1);
        bumpDraft(doc);
        return documentView(documents.findById(documentId).orElseThrow());
    }

    /**
     * 译文提交：必须匹配当前源文版本；译文版本递增，文档草稿版本加一。
     */
    @Transactional
    public TranslationView submitTranslation(String documentId, String segmentId, SubmitTranslationRequest req,
                                             String actor) {
        DocumentRow doc = lockDocument(documentId);
        SegmentRow segment = segments.find(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        if (isBlank(req.language()) || !documents.findLanguages(documentId).contains(req.language().trim())) {
            throw ApiException.unprocessable("非目标语言: " + req.language());
        }
        if (isBlank(req.body())) {
            throw ApiException.badRequest("body 不能为空");
        }
        if (req.sourceVersion() == null || req.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.conflict("源文版本不匹配: 当前为 " + segment.sourceVersion());
        }
        String language = req.language().trim();
        Instant now = Instant.now(clock);
        TranslationRow existing = translations.find(documentId, segmentId, language).orElse(null);
        int translationVersion;
        if (existing == null) {
            translationVersion = 1;
            translations.insert(documentId, segmentId, language, req.body(), actor,
                    segment.sourceVersion(), translationVersion, now);
        } else {
            translationVersion = existing.translationVersion() + 1;
            translations.update(documentId, segmentId, language, req.body(), actor,
                    segment.sourceVersion(), translationVersion, now);
        }
        bumpDraft(doc);
        return new TranslationView(segmentId, language, req.body(), actor, segment.sourceVersion(), translationVersion);
    }

    /**
     * 译文批准：审核人不得为译文作者；必须同时匹配当前源文版本与译文版本；
     * 译文必须基于当前源文版本（源文修订后须先重新提交译文）。
     */
    @Transactional
    public ApprovalView approve(String documentId, String segmentId, String language, ApproveRequest req,
                                String actor) {
        lockDocument(documentId);
        SegmentRow segment = segments.find(documentId, segmentId)
                .orElseThrow(() -> ApiException.notFound("段落不存在: " + segmentId));
        TranslationRow translation = translations.find(documentId, segmentId, language)
                .orElseThrow(() -> ApiException.notFound("译文不存在: " + segmentId + "/" + language));
        if (translation.author().equals(actor)) {
            throw ApiException.unprocessable("审核人不得是该译文作者");
        }
        if (translation.sourceVersion() != segment.sourceVersion()) {
            throw ApiException.unprocessable("译文待更新：译文基于源文版本 " + translation.sourceVersion()
                    + "，当前源文版本 " + segment.sourceVersion());
        }
        if (req.sourceVersion() == null || req.sourceVersion() != segment.sourceVersion()
                || req.translationVersion() == null
                || req.translationVersion() != translation.translationVersion()) {
            throw ApiException.conflict("版本不匹配: 当前源文版本 " + segment.sourceVersion()
                    + "，译文版本 " + translation.translationVersion());
        }
        Instant now = Instant.now(clock);
        translations.upsertApproval(documentId, segmentId, language, actor,
                segment.sourceVersion(), translation.translationVersion(), now);
        return new ApprovalView(segmentId, language, actor, segment.sourceVersion(),
                translation.translationVersion(), now);
    }

    /**
     * 发布：版本冲突 409；仅当全部段落在全部目标语言都有有效批准时，
     * 原子生成完整只读快照并递增发布版本；任何失败都不产生部分快照（同事务回滚）。
     */
    @Transactional
    public PublishView publish(String documentId, PublishRequest req) {
        DocumentRow doc = lockDocument(documentId);
        if (req.expectedDraftVersion() == null || req.expectedDraftVersion() != doc.draftVersion()
                || req.expectedPublishedVersion() == null
                || req.expectedPublishedVersion() != doc.publishedVersion()) {
            throw ApiException.conflict("版本冲突: 当前草稿版本 " + doc.draftVersion()
                    + "，发布版本 " + doc.publishedVersion());
        }
        List<String> languages = documents.findLanguages(documentId);
        List<SegmentRow> allSegments = segments.findAll(documentId);
        Map<String, TranslationRow> translationByKey = new LinkedHashMap<>();
        for (TranslationRow row : translations.findAll(documentId)) {
            translationByKey.put(row.segmentId() + " " + row.language(), row);
        }
        Set<String> validApprovals = new HashSet<>();
        for (ApprovalRow row : translations.findApprovals(documentId)) {
            validApprovals.add(row.segmentId() + " " + row.language() + " "
                    + row.sourceVersion() + " " + row.translationVersion());
        }
        for (SegmentRow segment : allSegments) {
            for (String language : languages) {
                TranslationRow translation = translationByKey.get(segment.segmentId() + " " + language);
                if (translation == null) {
                    throw ApiException.unprocessable(
                            "缺译: 段落 " + segment.segmentId() + " 语言 " + language);
                }
                String key = segment.segmentId() + " " + language + " "
                        + segment.sourceVersion() + " " + translation.translationVersion();
                if (!validApprovals.contains(key)) {
                    throw ApiException.unprocessable(
                            "审核失效或缺失: 段落 " + segment.segmentId() + " 语言 " + language);
                }
            }
        }
        int newVersion = doc.publishedVersion() + 1;
        Instant now = Instant.now(clock);
        publications.insertPublication(documentId, newVersion, now);
        for (SegmentRow segment : allSegments) {
            publications.insertSegment(documentId, newVersion, segment.segmentId(),
                    segment.sourceText(), segment.sourceVersion());
        }
        for (TranslationRow translation : translations.findAll(documentId)) {
            publications.insertTranslation(documentId, newVersion, translation.segmentId(), translation.language(),
                    translation.body(), translation.author(), translation.sourceVersion(),
                    translation.translationVersion());
        }
        documents.updatePublishedVersion(documentId, newVersion);
        return new PublishView(documentId, doc.draftVersion(), newVersion, now);
    }

    /**
     * 查询指定发布版本的完整只读快照。
     */
    @Transactional(readOnly = true)
    public PublicationView getPublication(String documentId, int publishedVersion) {
        Instant publishedAt = publications.findPublication(documentId, publishedVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "发布版本不存在: " + documentId + "/" + publishedVersion));
        List<SnapshotSegmentRow> snapshotSegments = publications.findSegments(documentId, publishedVersion);
        Map<String, List<TranslationView>> translationsBySegment = new LinkedHashMap<>();
        for (SnapshotTranslationRow row : publications.findTranslations(documentId, publishedVersion)) {
            translationsBySegment.computeIfAbsent(row.segmentId(), k -> new ArrayList<>())
                    .add(new TranslationView(row.segmentId(), row.language(), row.body(), row.author(),
                            row.sourceVersion(), row.translationVersion()));
        }
        List<SnapshotSegmentView> segmentViews = new ArrayList<>();
        for (SnapshotSegmentRow segment : snapshotSegments) {
            segmentViews.add(new SnapshotSegmentView(segment.segmentId(), segment.sourceText(),
                    segment.sourceVersion(),
                    translationsBySegment.getOrDefault(segment.segmentId(), List.of())));
        }
        return new PublicationView(documentId, publishedVersion, publishedAt, segmentViews);
    }

    private DocumentRow lockDocument(String documentId) {
        return documents.findByIdForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private void bumpDraft(DocumentRow doc) {
        documents.updateDraftVersion(doc.documentId(), doc.draftVersion() + 1);
    }

    private DocumentView documentView(DocumentRow doc) {
        List<SegmentView> segmentViews = new ArrayList<>();
        for (SegmentRow row : segments.findAll(doc.documentId())) {
            segmentViews.add(new SegmentView(row.segmentId(), row.sourceText(), row.sourceVersion()));
        }
        return new DocumentView(doc.documentId(), doc.draftVersion(), doc.publishedVersion(),
                documents.findLanguages(doc.documentId()), segmentViews, doc.createdAt());
    }

    private static List<String> validateLanguages(List<String> languages) {
        if (languages == null || languages.isEmpty() || languages.size() > 5) {
            throw ApiException.badRequest("目标语言数量必须为 1~5");
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String language : languages) {
            if (isBlank(language)) {
                throw ApiException.badRequest("目标语言不能为空");
            }
            String trimmed = language.trim();
            if (!seen.add(trimmed)) {
                throw ApiException.badRequest("目标语言重复: " + trimmed);
            }
            result.add(trimmed);
        }
        return result;
    }

    private static List<SegmentInput> validateSegments(List<SegmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw ApiException.badRequest("至少需要 1 个初始段落");
        }
        Set<String> seen = new HashSet<>();
        for (SegmentInput input : inputs) {
            if (input == null || isBlank(input.segmentId()) || isBlank(input.sourceText())) {
                throw ApiException.badRequest("段落的 segmentId 与 sourceText 均不能为空");
            }
            if (!seen.add(input.segmentId().trim())) {
                throw ApiException.badRequest("段落 ID 重复: " + input.segmentId());
            }
        }
        return inputs;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
