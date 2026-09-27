package com.example.starter.batch.dto;

import java.util.List;

/**
 * 标签封箱完整历史：计划概要 + 全部封箱版本（含已作废），按事件提交顺序（id 升序）。
 * 历史字段、排序与 null 语义稳定，后续封箱/作废不得改写既有行。
 */
public record LabelHistoryResponse(
        String batchKey,
        String batchStatus,
        LabelPlanResponse plan,
        List<SealRecordResponse> seals
) {
}
