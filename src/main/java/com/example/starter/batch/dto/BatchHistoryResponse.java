package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）
 * + 密封批次压力曲线（普通批次或未提交时 pressureCurve 为 null）。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        PressureCurveResponse pressureCurve
) {
}
