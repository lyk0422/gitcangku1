package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.ConflictDeclarationRow;
import com.example.starter.translation.domain.Rows.ConflictEventRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.ReviewHistoryRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 评审利益冲突服务：冲突声明的登记（单条/批量）、解除、明细历史与诊断查询。
 * 所有写操作在文档行锁内按提交顺序串行裁决；区间为 UTC 左闭右开；
 * 声明、事件与评审历史只追加，后续操作不改写当时业务证据。
 */
@Service
public class ConflictService {

    /** 解除操作要求的合规角色标识。 */
    public static final String COMPLIANCE_ROLE = "COMPLIANCE";

    private final TranslationRepository repository;
    private final ReviewConflictEvaluator evaluator;

    public ConflictService(TranslationRepository repository, ReviewConflictEvaluator evaluator) {
        this.repository = repository;
        this.evaluator = evaluator;
    }

    /** 单条登记冲突：区间不得倒置（422）、语种须属于文档（422）；与同评审人同语种 ACTIVE 声明重叠返回 409。 */
    @Transactional
    public ApiDtos.ConflictDeclarationView declare(long documentId, String actorId,
                                                   ApiDtos.DeclareConflictRequest request) {
        DocumentRow document = lockDocument(documentId);
        String language = normalizeLanguage(request.language());
        ensureLanguageInDocument(document, language);
        Instant now = evaluator.now();
        validateInterval(request.validFrom(), request.validTo());
        ensureNoOverlap(documentId, actorId, language, request.validFrom(), request.validTo());

        long declarationId = insertDeclarationAndEvent(documentId, actorId, language,
                request.validFrom(), request.validTo(), request.reason(), actorId, now);
        applyConflictedReviews(documentId, actorId, language, request.validFrom(), request.validTo(),
                declarationId, actorId, now);
        return toView(repository.findConflictDeclaration(documentId, declarationId).orElseThrow());
    }

    /**
     * 批量登记：先完整预校验全部范围（语种越界、区间倒置 422；与既有 ACTIVE 声明或批内相互重叠 409），
     * 任一非法整批回滚、不改变评审资格；全部通过后在同一事务内写入声明、事件与评审排除。
     */
    @Transactional
    public ApiDtos.BatchConflictResponse declareBatch(long documentId, String actorId,
                                                      ApiDtos.BatchDeclareConflictRequest request) {
        DocumentRow document = lockDocument(documentId);
        Instant now = evaluator.now();

        // 第一阶段：纯校验，不做任何写入。先归一化语种并校验区间与语种范围。
        record Range(String language, Instant validFrom, Instant validTo, String reason) {
        }
        List<Range> ranges = new ArrayList<>();
        for (ApiDtos.ConflictRangeInput input : request.declarations()) {
            String language = normalizeLanguage(input.language());
            ensureLanguageInDocument(document, language);
            validateInterval(input.validFrom(), input.validTo());
            ranges.add(new Range(language, input.validFrom(), input.validTo(), input.reason()));
        }
        // 批内同一语种区间相互重叠（含归一化后重复）→ 409。
        for (int i = 0; i < ranges.size(); i++) {
            Range current = ranges.get(i);
            for (int j = 0; j < i; j++) {
                Range previous = ranges.get(j);
                if (current.language().equals(previous.language())
                        && ReviewConflictEvaluator.overlaps(current.validFrom(), current.validTo(),
                        previous.validFrom(), previous.validTo())) {
                    throw ApiException.conflict("批量登记中第 " + (j + 1) + " 条与第 " + (i + 1)
                            + " 条冲突区间重叠: " + current.language());
                }
            }
        }
        // 与数据库中同评审人同语种 ACTIVE 声明重叠 → 409。
        for (Range range : ranges) {
            ensureNoOverlap(documentId, actorId, range.language(), range.validFrom(), range.validTo());
        }

        // 第二阶段：全部校验通过后，一个事务内顺序提交。
        List<ApiDtos.ConflictDeclarationView> views = new ArrayList<>();
        for (Range range : ranges) {
            long declarationId = insertDeclarationAndEvent(documentId, actorId, range.language(),
                    range.validFrom(), range.validTo(), range.reason(), actorId, now);
            applyConflictedReviews(documentId, actorId, range.language(), range.validFrom(), range.validTo(),
                    declarationId, actorId, now);
            views.add(toView(repository.findConflictDeclaration(documentId, declarationId).orElseThrow()));
        }
        return new ApiDtos.BatchConflictResponse(documentId, actorId, views.size(), views);
    }

    /**
     * 解除冲突：声明须存在（404）且处于 ACTIVE（409）；确认人角色须为 COMPLIANCE（403）、
     * 且不得是声明人本人（403）。解除追加 RELEASED 事件，并将仍由该声明排除的评审恢复为 VALID，
     * 每条恢复追加评审历史；只影响后续评审与发布，不追溯旧快照。
     */
    @Transactional
    public ApiDtos.ConflictDeclarationView release(long documentId, long declarationId, String actorId,
                                                   String actorRole, ApiDtos.ReleaseConflictRequest request) {
        lockDocument(documentId);
        ConflictDeclarationRow declaration = repository.findConflictDeclaration(documentId, declarationId)
                .orElseThrow(() -> ApiException.notFound(
                        "冲突声明不存在: " + documentId + "/" + declarationId));
        if (!COMPLIANCE_ROLE.equalsIgnoreCase(actorRole == null ? "" : actorRole.trim())) {
            throw ApiException.forbidden("解除冲突须由 COMPLIANCE 合规角色确认");
        }
        if (actorId.equals(declaration.declaredBy())) {
            throw ApiException.forbidden("解除确认人不得是声明人本人: " + actorId);
        }
        if (!"ACTIVE".equals(declaration.status())) {
            throw ApiException.conflict("冲突声明已解除，不能重复解除: " + declarationId);
        }
        Instant now = evaluator.now();

        // 先取仍被该声明排除的评审（恢复后用于追加历史），再置 RELEASED 与恢复资格。
        List<ApprovalRow> conflicted = repository.listConflictedApprovals(documentId, declarationId);
        int released = repository.markConflictReleased(documentId, declarationId, actorId, now);
        if (released == 0) {
            throw ApiException.conflict("冲突声明已被并发解除: " + declarationId);
        }
        repository.insertConflictEvent(declarationId, documentId, "RELEASED",
                declaration.reviewer(), declaration.language(), declaration.validFrom(), declaration.validTo(),
                declaration.reason(), actorId, COMPLIANCE_ROLE, now);
        repository.restoreApprovalsForDeclaration(documentId, declarationId);
        for (ApprovalRow approval : conflicted) {
            repository.insertReviewHistory(documentId, approval.segmentId(), approval.language(),
                    approval.reviewer(), approval.decision(), "VALID", approval.sourceVersion(),
                    approval.translationVersion(), approval.termVersion(), null, actorId, now);
        }
        return toView(repository.findConflictDeclaration(documentId, declarationId).orElseThrow());
    }

    /** 查询文档全部冲突声明明细，按声明 ID 升序；只读，不改变状态。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.ConflictDeclarationView> listConflicts(long documentId) {
        requireDocument(documentId);
        return repository.listConflictDeclarations(documentId).stream().map(ConflictService::toView).toList();
    }

    /** 查询冲突生命周期历史，按事件 ID 升序；可按声明过滤；只读。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.ConflictEventView> listConflictEvents(long documentId, Long declarationId) {
        requireDocument(documentId);
        List<ConflictDeclarationRow> declarations;
        if (declarationId != null) {
            ConflictDeclarationRow declaration = repository.findConflictDeclaration(documentId, declarationId)
                    .orElseThrow(() -> ApiException.notFound(
                            "冲突声明不存在: " + documentId + "/" + declarationId));
            declarations = List.of(declaration);
        } else {
            declarations = repository.listConflictDeclarations(documentId);
        }
        List<ApiDtos.ConflictEventView> views = new ArrayList<>();
        for (ConflictDeclarationRow declaration : declarations) {
            repository.listConflictEventsForDeclaration(documentId, declaration.declarationId())
                    .stream().map(ConflictService::toEventView).forEach(views::add);
        }
        return views;
    }

    /** 查询评审历史，按历史 ID 升序；可按段落、语种过滤；只读。 */
    @Transactional(readOnly = true)
    public List<ApiDtos.ReviewHistoryView> listReviewHistory(long documentId, String segmentId, String language) {
        requireDocument(documentId);
        String normalizedLanguage = language == null || language.isBlank()
                ? null : normalizeLanguage(language);
        return repository.listReviewHistory(documentId).stream()
                .filter(row -> segmentId == null || segmentId.isBlank() || segmentId.equals(row.segmentId()))
                .filter(row -> normalizedLanguage == null || normalizedLanguage.equals(row.language()))
                .map(ConflictService::toReviewHistoryView)
                .toList();
    }

    /**
     * 评审诊断：当前时刻全部有效冲突声明、全部评审的资格明细，以及按当前译文状态重算的法定人数
     *（要求数 = 段落数 × 语种数；实际有效批准数与差额）。只读，不改变任何状态。
     */
    @Transactional(readOnly = true)
    public ApiDtos.ReviewDiagnosticsResponse diagnostics(long documentId) {
        DocumentRow document = requireDocument(documentId);
        Instant now = evaluator.now();
        List<SegmentRow> segments = repository.listSegments(documentId);
        List<ConflictDeclarationRow> allDeclarations = repository.listConflictDeclarations(documentId);
        List<ConflictDeclarationRow> activeConflicts = allDeclarations.stream()
                .filter(declaration -> "ACTIVE".equals(declaration.status()))
                .filter(declaration -> ReviewConflictEvaluator.contains(declaration, now))
                .toList();

        int required = segments.size() * document.targetLanguages().size();
        List<ApiDtos.ReviewEligibilityView> reviews = new ArrayList<>();
        int effective = 0;
        for (ApprovalRow approval : repository.listApprovals(documentId)) {
            String reason;
            boolean eligible = false;
            Long blockingDeclarationId = null;
            ConflictDeclarationRow conflict = evaluator.findEffectiveConflict(
                    documentId, approval.reviewer(), approval.language(), now);
            if ("REJECTED".equals(approval.decision())) {
                reason = "评审决定为否决，不计入法定人数";
            } else if (conflict != null) {
                blockingDeclarationId = conflict.declarationId();
                reason = "评审人命中有效利益冲突，声明 " + conflict.declarationId() + "，已排除出法定人数";
            } else {
                eligible = true;
                effective++;
                reason = "有效批准，计入法定人数";
            }
            reviews.add(new ApiDtos.ReviewEligibilityView(approval.segmentId(), approval.language(),
                    approval.reviewer(), approval.decision(), approval.reviewStatus(),
                    approval.sourceVersion(), approval.translationVersion(), eligible, blockingDeclarationId, reason));
        }
        return new ApiDtos.ReviewDiagnosticsResponse(documentId, now,
                activeConflicts.stream().map(ConflictService::toView).toList(),
                List.copyOf(reviews), required, effective, Math.max(0, required - effective));
    }

    // ---------------- 内部方法（置于作用域末尾） ----------------

    private long insertDeclarationAndEvent(long documentId, String reviewer, String language,
                                           Instant validFrom, Instant validTo, String reason,
                                           String declaredBy, Instant now) {
        long declarationId = repository.insertConflictDeclaration(documentId, reviewer, language,
                validFrom, validTo, reason, declaredBy, now);
        repository.insertConflictEvent(declarationId, documentId, "DECLARED", reviewer, language,
                validFrom, validTo, reason, declaredBy, null, now);
        return declarationId;
    }

    /**
     * 声明在登记时刻即有效（now 落在左闭右开区间内）时，将该评审人在该语种的全部 VALID 评审
     * 标记为 CONFLICTED 并追加评审历史；未来区间不提前标记，提交时与发布时再按有效冲突判定。
     */
    private void applyConflictedReviews(long documentId, String reviewer, String language,
                                        Instant validFrom, Instant validTo, long declarationId,
                                        String declaredBy, Instant now) {
        if (now.isBefore(validFrom) || !now.isBefore(validTo)) {
            return;
        }
        for (ApprovalRow approval : repository.listApprovalsByReviewer(documentId, reviewer, language)) {
            if (!"VALID".equals(approval.reviewStatus())) {
                continue;
            }
            int updated = repository.markApprovalConflicted(documentId, approval.segmentId(), language,
                    declarationId, now);
            if (updated > 0) {
                repository.insertReviewHistory(documentId, approval.segmentId(), language, reviewer,
                        approval.decision(), "CONFLICTED", approval.sourceVersion(),
                        approval.translationVersion(), approval.termVersion(), declarationId, declaredBy, now);
            }
        }
    }

    private void ensureNoOverlap(long documentId, String reviewer, String language,
                                 Instant validFrom, Instant validTo) {
        for (ConflictDeclarationRow existing : repository.listReviewerConflicts(documentId, reviewer, language)) {
            if (!"ACTIVE".equals(existing.status())) {
                continue;
            }
            if (ReviewConflictEvaluator.overlaps(validFrom, validTo, existing.validFrom(), existing.validTo())) {
                throw ApiException.conflict("与同评审人同语种已生效的冲突声明 " + existing.declarationId()
                        + " 区间重叠: " + language);
            }
        }
    }

    private static void validateInterval(Instant validFrom, Instant validTo) {
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.unprocessable("冲突区间不得倒置：validFrom 必须严格早于 validTo");
        }
    }

    private static void ensureLanguageInDocument(DocumentRow document, String language) {
        if (!document.targetLanguages().contains(language)) {
            throw ApiException.unprocessable("语言不在文档目标语言中: " + language);
        }
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private DocumentRow requireDocument(long documentId) {
        return repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiDtos.ConflictDeclarationView toView(ConflictDeclarationRow row) {
        return new ApiDtos.ConflictDeclarationView(row.declarationId(), row.documentId(), row.reviewer(),
                row.language(), row.validFrom(), row.validTo(), row.reason(), row.status(),
                row.declaredBy(), row.declaredAt(), row.releasedAt(), row.releasedBy());
    }

    private static ApiDtos.ConflictEventView toEventView(ConflictEventRow row) {
        return new ApiDtos.ConflictEventView(row.eventId(), row.declarationId(), row.eventType(),
                row.reviewer(), row.language(), row.validFrom(), row.validTo(), row.reason(),
                row.actor(), row.actorRole(), row.occurredAt());
    }

    private static ApiDtos.ReviewHistoryView toReviewHistoryView(ReviewHistoryRow row) {
        return new ApiDtos.ReviewHistoryView(row.historyId(), row.segmentId(), row.language(),
                row.reviewer(), row.decision(), row.reviewStatus(), row.sourceVersion(),
                row.translationVersion(), row.termVersion(), row.conflictDeclarationId(),
                row.actor(), row.occurredAt());
    }
}
