package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部测量向量 + 全部批准 + 召回记录
 * （未召回时 recall 为 null）。历史不因召回或规范换版而删除或改写。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<MeasurementResponse> measurements,
        List<ApprovalResponse> approvals,
        RecallResponse recall
) {
}
