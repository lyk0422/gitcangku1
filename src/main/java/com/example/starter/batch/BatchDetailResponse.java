package com.example.starter.batch;

import java.time.Instant;
import java.util.List;

/**
 * 批次完整历史明细：批次字段、必做检验项、全部检验结果、批准记录与召回记录。
 * 召回后历史保留，不得删除或改写。
 *
 * @param batchKey      批次业务键
 * @param productCode   产品编码
 * @param lotNumber     批号
 * @param producedAt    生产时间（UTC）
 * @param status        当前批次状态
 * @param requiredItems 必做检验项
 * @param tests         检验结果历史（按提交时间升序）
 * @param approvals     批准历史（按批准时间升序）
 * @param recall        召回记录；未召回时为 null
 */
public record BatchDetailResponse(
        String batchKey,
        String productCode,
        String lotNumber,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredItems,
        List<TestResultResponse> tests,
        List<ApprovalResponse> approvals,
        RecallResponse recall) {
}
