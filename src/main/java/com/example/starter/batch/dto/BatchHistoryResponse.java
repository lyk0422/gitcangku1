package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 合格证绑定（按绑定提交顺序）+ 放行快照（无声明约束的放行或未放行时为 null）
 * + 合批父批业务键（非合批产生时为空列表）。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        List<BindingResponse> certificateBindings,
        ReleaseSnapshotResponse releaseSnapshot,
        List<String> mergedFrom
) {
}