package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.ConflictHistoryRow;
import com.example.starter.translation.domain.Rows.ConflictRow;
import com.example.starter.translation.domain.Rows.DocumentRow;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.domain.Rows.TranslationRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 译文评审利益冲突服务：冲突声明的批量登记、合规解除与明细/历史/诊断查询。
 * 写操作同样先锁文档行，与评审、发布按提交顺序串行裁决；
 * 声明、解除与排除均追加历史/证据记录，后续操作不改写当时的业务证据。
 */
@Service
public class ConflictService {

    private final TranslationRepository repository;
    private final Clock clock;

    public ConflictService(TranslationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 批量登记冲突声明：先预校验全部范围（语言须在目标语言中、区间左闭右开不倒置、
     * 批内及与既有 ACTIVE 声明不重叠），任一非法整批不改变评审资格；
     * 全部通过后逐条登记并追加 DECLARED 历史，当前已有效的声明立即将命中评审人的
     * ACTIVE 批准标记 CONFLICTED 并追加排除记录。
     */
    @Transactional
    public ApiDtos.DeclareConflictsResponse declareConflicts(long documentId, String actorId,
                                                             ApiDtos.DeclareConflictsRequest request) {
        DocumentRow document = lockDocument(documentId);
        Instant now = nowUtc();
        List<NormalizedDeclaration> declarations = new ArrayList<>();
        for (ApiDtos.ConflictDeclarationInput input : request.declarations()) {
            String language = normalizeLanguage(input.language());
            if (!document.targetLanguages().contains(language)) {
                throw ApiException.unprocessable("冲突声明语言不在文档目标语言中: " + language);
            }
            Instant validFrom = input.validFrom().truncatedTo(ChronoUnit.SECONDS);
            Instant validTo = input.validTo().truncatedTo(ChronoUnit.SECONDS);
            if (!validFrom.isBefore(validTo)) {
                throw ApiException.unprocessable("冲突区间非法：validFrom " + validFrom
                        + " 必须早于 validTo " + validTo + "（左闭右开，不得倒置或为空）");
            }
            declarations.add(new NormalizedDeclaration(input.reviewer().trim(), language,
                    validFrom, validTo, input.reason().trim()));
        }
        List<ConflictRow> activeExisting = listActiveConflicts(documentId);
        for (int i = 0; i < declarations.size(); i++) {
            NormalizedDeclaration declaration = declarations.get(i);
            for (ConflictRow existing : activeExisting) {
                if (sameScope(existing.reviewer(), existing.language(), declaration)
                        && overlaps(existing.validFrom(), existing.validTo(),
                        declaration.validFrom(), declaration.validTo())) {
                    throw ApiException.conflict("评审人 " + declaration.reviewer() + " 在 "
                            + declaration.language() + " 的声明区间与已有声明 "
                            + existing.conflictId() + " 重叠");
                }
            }
            for (int j = 0; j < i; j++) {
                NormalizedDeclaration prior = declarations.get(j);
                if (sameScope(prior.reviewer(), prior.language(), declaration)
                        && overlaps(prior.validFrom(), prior.validTo(),
                        declaration.validFrom(), declaration.validTo())) {
                    throw ApiException.conflict("批量声明内部区间重叠：评审人 " + declaration.reviewer()
                            + " 在 " + declaration.language() + " 的第 " + (j + 1) + " 与第 " + (i + 1) + " 条");
                }
            }
        }
        List<ApprovalRow> approvals = repository.listApprovals(documentId);
        Set<String> marked = new HashSet<>();
        List<ApiDtos.ConflictView> views = new ArrayList<>();
        int excludedReviewCount = 0;
        for (NormalizedDeclaration declaration : declarations) {
            long conflictId = repository.insertConflict(documentId, declaration.reviewer(),
                    declaration.language(), declaration.reason(), Timestamp.from(declaration.validFrom()),
                    Timestamp.from(declaration.validTo()), actorId, Timestamp.from(now));
            repository.insertConflictHistory(conflictId, documentId, "DECLARED", actorId,
                    declaration.reason(), Timestamp.from(declaration.validFrom()),
                    Timestamp.from(declaration.validTo()), Timestamp.from(now));
            if (!declaration.validFrom().isAfter(now) && declaration.validTo().isAfter(now)) {
                for (ApprovalRow approval : approvals) {
                    if ("ACTIVE".equals(approval.status())
                            && approval.reviewer().equals(declaration.reviewer())
                            && approval.language().equals(declaration.language())
                            && marked.add(approval.segmentId() + " " + approval.language())) {
                        repository.markApprovalConflicted(documentId, approval.segmentId(),
                                approval.language(), conflictId, Timestamp.from(now));
                        repository.insertExclusion(documentId, approval.segmentId(), approval.language(),
                                approval.reviewer(), approval.sourceVersion(),
                                approval.translationVersion(), conflictId, Timestamp.from(now));
                        excludedReviewCount++;
                    }
                }
            }
            views.add(new ApiDtos.ConflictView(conflictId, documentId, declaration.reviewer(),
                    declaration.language(), declaration.reason(), declaration.validFrom(),
                    declaration.validTo(), "ACTIVE", actorId, now, null, null, null));
        }
        return new ApiDtos.DeclareConflictsResponse(documentId, views.size(), excludedReviewCount, views);
    }

    /**
     * 解除冲突声明：须由合规角色（X-Actor-Role: COMPLIANCE）且不同于声明人确认，
     * 追加 LIFTED 历史；只影响后续评审与发布，不追溯使旧快照失效，也不恢复已排除的批准。
     */
    @Transactional
    public ApiDtos.ConflictView liftConflict(long documentId, long conflictId, String actorId,
                                             String actorRole, ApiDtos.LiftConflictRequest request) {
        lockDocument(documentId);
        ConflictRow conflict = repository.findConflict(documentId, conflictId)
                .orElseThrow(() -> ApiException.notFound("冲突声明不存在: " + documentId + "/" + conflictId));
        if (actorRole == null || !"COMPLIANCE".equalsIgnoreCase(actorRole.trim())) {
            throw ApiException.unprocessable("解除冲突须由合规角色确认（X-Actor-Role: COMPLIANCE），实际角色: "
                    + (actorRole == null ? "未提供" : actorRole));
        }
        if (conflict.declaredBy().equals(actorId)) {
            throw ApiException.unprocessable("解除确认人须不同于声明人 " + conflict.declaredBy());
        }
        if (!"ACTIVE".equals(conflict.status())) {
            throw ApiException.conflict("冲突声明已解除: " + conflictId);
        }
        Instant now = nowUtc();
        repository.liftConflict(conflictId, actorId, Timestamp.from(now), request.reason().trim());
        repository.insertConflictHistory(conflictId, documentId, "LIFTED", actorId, request.reason().trim(),
                Timestamp.from(conflict.validFrom()), Timestamp.from(conflict.validTo()),
                Timestamp.from(now));
        return new ApiDtos.ConflictView(conflict.conflictId(), documentId, conflict.reviewer(),
                conflict.language(), conflict.reason(), conflict.validFrom(), conflict.validTo(),
                "LIFTED", conflict.declaredBy(), conflict.declaredAt(), actorId, now,
                request.reason().trim());
    }

    /** 冲突声明明细查询：按 conflictId 升序，只读。 */
    @Transactional(readOnly = true)
    public ApiDtos.ConflictListResponse listConflicts(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        return new ApiDtos.ConflictListResponse(documentId,
                repository.listConflicts(documentId).stream().map(ConflictService::toView).toList());
    }

    /** 冲突历史查询：登记与解除按 historyId 升序，只读。 */
    @Transactional(readOnly = true)
    public ApiDtos.ConflictHistoryResponse listHistory(long documentId) {
        repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        List<ApiDtos.ConflictHistoryView> records = new ArrayList<>();
        for (ConflictHistoryRow row : repository.listConflictHistory(documentId)) {
            records.add(new ApiDtos.ConflictHistoryView(row.historyId(), row.conflictId(), row.action(),
                    row.actor(), row.reason(), row.validFrom(), row.validTo(), row.recordedAt()));
        }
        return new ApiDtos.ConflictHistoryResponse(documentId, records);
    }

    /**
     * 冲突诊断：按当前状态重算法定人数缺口（要求/有效/缺失评审数与受影响范围）、
     * 当前有效冲突与已被排除的评审数；只读，不改变任何状态。
     */
    @Transactional(readOnly = true)
    public ApiDtos.ConflictDiagnosticsView diagnostics(long documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
        Instant now = nowUtc();
        List<ConflictRow> activeConflicts = listActiveConflicts(documentId);
        List<SegmentRow> segments = repository.listSegments(documentId);
        Map<String, TranslationRow> translations = repository.listTranslations(documentId).stream()
                .collect(Collectors.toMap(t -> t.segmentId() + " " + t.language(), Function.identity()));
        Map<String, ApprovalRow> approvals = repository.listApprovals(documentId).stream()
                .collect(Collectors.toMap(a -> a.segmentId() + " " + a.language(), Function.identity()));
        int requiredReviews = 0;
        int effectiveReviews = 0;
        int conflictedReviewCount = 0;
        List<ApiDtos.ScopeView> affectedScopes = new ArrayList<>();
        for (SegmentRow segment : segments) {
            for (String language : document.targetLanguages()) {
                requiredReviews++;
                ApprovalRow approval = approvals.get(segment.segmentId() + " " + language);
                TranslationRow translation = translations.get(segment.segmentId() + " " + language);
                boolean effective = approval != null && "ACTIVE".equals(approval.status())
                        && translation != null
                        && approval.translationVersion() == translation.translationVersion()
                        && approval.sourceVersion() == segment.sourceVersion()
                        && findEffectiveConflict(activeConflicts, approval.reviewer(), language, now) == null;
                if (effective) {
                    effectiveReviews++;
                } else {
                    affectedScopes.add(new ApiDtos.ScopeView(segment.segmentId(), language));
                }
            }
        }
        for (ApprovalRow approval : repository.listApprovals(documentId)) {
            if ("CONFLICTED".equals(approval.status())) {
                conflictedReviewCount++;
            }
        }
        List<ApiDtos.ConflictView> effectiveConflicts = activeConflicts.stream()
                .filter(c -> !c.validFrom().isAfter(now) && c.validTo().isAfter(now))
                .map(ConflictService::toView)
                .toList();
        return new ApiDtos.ConflictDiagnosticsView(documentId, now, requiredReviews, effectiveReviews,
                requiredReviews - effectiveReviews, conflictedReviewCount, affectedScopes,
                effectiveConflicts);
    }

    private DocumentRow lockDocument(long documentId) {
        return repository.findDocumentForUpdate(documentId)
                .orElseThrow(() -> ApiException.notFound("文档不存在: " + documentId));
    }

    private List<ConflictRow> listActiveConflicts(long documentId) {
        return repository.listConflicts(documentId).stream()
                .filter(c -> "ACTIVE".equals(c.status()))
                .toList();
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String normalizeLanguage(String language) {
        return language.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameScope(String reviewer, String language, NormalizedDeclaration declaration) {
        return reviewer.equals(declaration.reviewer()) && language.equals(declaration.language());
    }

    /** 左闭右开区间重叠判定：[aFrom,aTo) 与 [bFrom,bTo) 有交集。 */
    private static boolean overlaps(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
        return aFrom.isBefore(bTo) && bFrom.isBefore(aTo);
    }

    private static ConflictRow findEffectiveConflict(List<ConflictRow> activeConflicts, String reviewer,
                                                     String language, Instant now) {
        for (ConflictRow conflict : activeConflicts) {
            if (conflict.reviewer().equals(reviewer) && conflict.language().equals(language)
                    && !conflict.validFrom().isAfter(now) && conflict.validTo().isAfter(now)) {
                return conflict;
            }
        }
        return null;
    }

    private static ApiDtos.ConflictView toView(ConflictRow row) {
        return new ApiDtos.ConflictView(row.conflictId(), row.documentId(), row.reviewer(), row.language(),
                row.reason(), row.validFrom(), row.validTo(), row.status(), row.declaredBy(),
                row.declaredAt(), row.liftedBy(), row.liftedAt(), row.liftReason());
    }

    /** 规范化后的冲突声明：语言小写、区间截断到秒。 */
    private record NormalizedDeclaration(String reviewer, String language, Instant validFrom,
                                         Instant validTo, String reason) {
    }
}
