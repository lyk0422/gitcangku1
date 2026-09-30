package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）。
 * mergeBasis 仅合批子批非空（与批次概要中的冻结依据一致）；
 * mergedIntoChildKey 仅参与过合批的父批非空，指向其被并入的合批子批业务键。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        MergeBasisResponse mergeBasis,
        String mergedIntoChildKey
) {
}
