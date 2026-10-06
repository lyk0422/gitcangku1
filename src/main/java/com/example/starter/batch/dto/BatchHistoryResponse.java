package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史：批次概要 + 全部检验结果 + 全部平行测定轮次（含原始读数）
 * + 全部批准 + 召回记录，历史不因召回而删除或改写。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<AssayRoundDetailResponse> assayRounds,
        List<ApprovalResponse> approvals,
        RecallResponse recall
) {
}
