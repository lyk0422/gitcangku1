package com.example.starter.batch.dto;

import com.example.starter.certificate.dto.BindingResponse;
import com.example.starter.certificate.dto.ReleaseSnapshotResponse;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 合格证绑定（未绑定时 binding 为 null）+ 放行快照（未放行时 releaseSnapshot 为 null）。
 * 历史字段、排序、时间精度与 null 语义稳定，后续操作不改写当时业务证据。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        BindingResponse binding,
        ReleaseSnapshotResponse releaseSnapshot
) {
}
