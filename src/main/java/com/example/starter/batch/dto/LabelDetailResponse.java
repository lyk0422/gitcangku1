package com.example.starter.batch.dto;

import java.util.List;

/**
 * 标签核销明细/历史查询响应：plan 为 null 表示该批次未登记包装计划；
 * seals 明细查询仅含活跃封箱，历史查询含全部封箱（含已作废），均按提交顺序。
 */
public record LabelDetailResponse(
        String batchKey,
        PackagingPlanResponse plan,
        List<SealResponse> seals,
        LabelSummary summary
) {
}
