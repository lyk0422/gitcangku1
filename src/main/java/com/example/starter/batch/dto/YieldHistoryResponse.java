package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次产率核算历史：按版本号升序的全部核算版本。
 * 每个版本保留其计算时冻结的允许上限与两名处置人，不随上限调整回写。
 */
public record YieldHistoryResponse(
        String batchKey,
        List<YieldReconcileResponse> versions
) {
}
