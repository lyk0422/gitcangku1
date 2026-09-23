package com.example.starter.restitution.dto;

import java.util.List;

/**
 * 案件整案视图：OPEN 时 source=LIVE 取实时数据；DECIDED 后 source=FROZEN 取裁决快照，不重算。
 *
 * @param caseId      案件编号
 * @param status      案件状态
 * @param version     案件版本
 * @param items       全案藏品清单
 * @param source      LIVE/FROZEN
 * @param claims      主张明细（DECIDED 后仅含中选主张的冻结快照）
 * @param frozenItems 裁决冻结的藏品归属（仅 DECIDED）
 * @param decidedAt   裁决时间（epoch 毫秒），未裁决为空
 */
public record CaseDetailResponse(
        String caseId,
        String status,
        long version,
        List<String> items,
        String source,
        List<ClaimDetail> claims,
        List<FrozenItemView> frozenItems,
        Long decidedAt) {
}
