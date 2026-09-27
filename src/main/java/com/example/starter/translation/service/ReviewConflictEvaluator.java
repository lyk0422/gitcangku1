package com.example.starter.translation.service;

import com.example.starter.translation.domain.Rows.ApprovalRow;
import com.example.starter.translation.domain.Rows.ConflictDeclarationRow;
import com.example.starter.translation.repo.TranslationRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 评审利益冲突判定：区间端点为 UTC、左闭右开 [validFrom, validTo)。
 * 声明 ACTIVE 且评估时刻落在区间内即为“有效冲突”：评审人不得在该语种提交评审，已有评审被排除出法定人数。
 * 解除（RELEASED）后的声明不再判定为有效；历史快照不受影响。
 */
@Component
public class ReviewConflictEvaluator {

    private final TranslationRepository repository;
    private final TimeProvider timeProvider;

    public ReviewConflictEvaluator(TranslationRepository repository, TimeProvider timeProvider) {
        this.repository = repository;
        this.timeProvider = timeProvider;
    }

    /** 当前 UTC 时刻。 */
    public Instant now() {
        return timeProvider.now();
    }

    /** 判断评审人在指定文档、语种、时刻是否命中有效冲突；命中时返回第一条（按声明 ID 升序）声明。 */
    public ConflictDeclarationRow findEffectiveConflict(long documentId, String reviewer, String language,
                                                        Instant at) {
        return effectiveConflicts(documentId, reviewer, language, at).stream().findFirst().orElse(null);
    }

    /** 列出评审人在指定文档、语种、时刻的全部有效冲突声明，按声明 ID 升序稳定返回。 */
    public List<ConflictDeclarationRow> effectiveConflicts(long documentId, String reviewer, String language,
                                                           Instant at) {
        return repository.listReviewerConflicts(documentId, reviewer, language).stream()
                .filter(declaration -> "ACTIVE".equals(declaration.status()))
                .filter(declaration -> contains(declaration, at))
                .toList();
    }

    /** 区间是否包含指定时刻：左闭右开。 */
    public static boolean contains(ConflictDeclarationRow declaration, Instant at) {
        return !at.isBefore(declaration.validFrom()) && at.isBefore(declaration.validTo());
    }

    /** 两个左闭右开区间是否重叠（相邻端点不算重叠）。 */
    public static boolean overlaps(Instant fromA, Instant toA, Instant fromB, Instant toB) {
        return fromA.isBefore(toB) && fromB.isBefore(toA);
    }

    /**
     * 一条评审在指定时刻是否可计入法定人数：决定为 APPROVED、资格 VALID 且未命中任何有效冲突。
     */
    public boolean isEligible(long documentId, ApprovalRow approval, Instant at) {
        if (!"APPROVED".equals(approval.decision()) || !"VALID".equals(approval.reviewStatus())) {
            return false;
        }
        return effectiveConflicts(documentId, approval.reviewer(), approval.language(), at).isEmpty();
    }
}
