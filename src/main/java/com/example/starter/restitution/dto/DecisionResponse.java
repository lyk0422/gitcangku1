package com.example.starter.restitution.dto;

import java.util.List;

/**
 * 裁决成功响应。
 *
 * @param caseId            案件编号
 * @param status            固定 DECIDED
 * @param version           裁决后案件版本
 * @param selectedClaimKeys 中选主张键
 * @param frozenItems       冻结的藏品到申请人对应关系
 */
public record DecisionResponse(
        String caseId,
        String status,
        long version,
        List<String> selectedClaimKeys,
        List<FrozenItemView> frozenItems) {
}
