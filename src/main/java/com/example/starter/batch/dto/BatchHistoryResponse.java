package com.example.starter.batch.dto;

import com.example.starter.certificate.dto.CertificateBindingInfo;
import com.example.starter.certificate.dto.ReleaseSnapshotInfo;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 合格证绑定（未绑定时 certificate 为 null）+ 放行快照（未放行时 releaseSnapshot 为 null）。
 * 既有字段顺序与语义保持稳定；绑定与快照为业务时点固化证据，随后续操作不改写。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        CertificateBindingInfo certificate,
        ReleaseSnapshotInfo releaseSnapshot
) {
}