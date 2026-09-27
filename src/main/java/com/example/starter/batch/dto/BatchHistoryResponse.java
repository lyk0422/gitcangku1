package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录 + 效价固化计算记录。
 * 历史不因召回或模型修订而删除或改写；recall 未召回时为 null；
 * potencyCalculations 按固化顺序（id 升序）返回，未登记效价模型时为空数组；字段仅末尾追加。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        List<PotencyCalculationView> potencyCalculations
) {
}
