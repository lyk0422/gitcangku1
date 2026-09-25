package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 全部条件放行记录（含已到期降级与已核销子项，按创建顺序稳定排序，永不物理删除）。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        List<ConditionReleaseResponse> conditions
) {
}
