package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 当前效价模型（未登记时 potencyModel 为 null）+ 全部固化效价计算记录（按写入顺序，
 * 无记录时为空列表）。历史记录一经写入不改写。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        PotencyModelResponse potencyModel,
        List<PotencyEvaluationResponse> potencyEvaluations
) {
}
