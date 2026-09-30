package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 案件冻结历史视图。
 *
 * @param caseKey 案件键
 * @param holds    该案件的全部案件级冻结（含生效、已解除、已到期，按发生顺序）
 */
public record CaseHoldsView(
        String caseKey,
        List<HoldView> holds) {
}
