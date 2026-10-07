package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 合成序贯结果 + 全部批准 + 召回记录
 * （未召回时 recall 为 null；非序贯批次 sequentialResults 为空数组）。
 * 序贯结果终止后前缀、分数与结论冻结，历史不随后代拆分或召回变化。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<SequentialResultResponse> sequentialResults,
        List<ApprovalResponse> approvals,
        RecallResponse recall
) {
}
