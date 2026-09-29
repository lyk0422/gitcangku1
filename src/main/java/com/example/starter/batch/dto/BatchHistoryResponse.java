package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 放行固化的合格证快照（未放行时为空列表，放行后按物料字典序排列，永不改写）。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        List<ReleaseCertificateSnapshotResponse> releaseCertificateSnapshots
) {
}
