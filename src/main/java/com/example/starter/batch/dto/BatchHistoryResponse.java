package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 配方绑定（未绑定时 recipeBinding 为 null）+ 全部称量记录（含已作废）。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        RecipeBindingResponse recipeBinding,
        List<WeighingRecordView> weighings
) {
}