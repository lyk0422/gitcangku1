package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录
 * + 全部储运偏差（含裁决快照）+ 全部风险记录（未召回时 recall 为 null）。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        List<ExcursionResponse> excursions,
        List<RiskEventResponse> riskEvents
) {
}
