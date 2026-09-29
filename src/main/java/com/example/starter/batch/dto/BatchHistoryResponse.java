package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 全部产率核算版本（含各版本冻结上限快照与两名处置人，未提交时为空列表）。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        List<YieldVersionResponse> yields
) {
}
