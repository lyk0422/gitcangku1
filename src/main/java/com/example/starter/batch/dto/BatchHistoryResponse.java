package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次完整历史明细：批次概要 + 全部检验结果 + 全部批准 + 召回记录（未召回时 recall 为 null）。
 * merge 仅当该批次是合批子批时非空，给出合批冻结依据（父批过敏原集合、所用清洁代次与状态快照），
 * 该依据在合批落定后固化，不随后续过敏原保存或清洁推进改写。
 */
public record BatchHistoryResponse(
        BatchResponse batch,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall,
        MergeResponse merge
) {
}
