package com.example.starter.batch.dto;

import java.util.List;

/**
 * 复检缺口查询响应：以 batchKey 为根的最终血缘闭包（规范排序）及其中未满足解除条件的缺口。
 * gap 取值：MISSING_RETEST 无复检记录 / FAILED_RETEST 最新复检不合格 / PENDING_QUARANTINE 未决隔离。
 */
public record RetestGapResponse(
        String batchKey,
        List<String> closureBatches,
        List<RetestGap> gaps
) {
    /**
     * 单个缺口条目：批次键 + 缺口原因。
     */
    public record RetestGap(String batchKey, String gap) {
    }
}
