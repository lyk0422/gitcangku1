package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。全部父批状态落定 MERGED 并退出可用集合；新批初始 QUARANTINED，
 * 产品编码与必做检验项继承父批，生产 UTC 时间取父批最晚值，不继承检验或批准记录。
 * mergedAt 为 UTC instant。
 */
public record MergeResponse(
        List<String> parentBatchKeys,
        BatchStatus parentStatus,
        MergedChild child,
        Instant mergedAt
) {

    /**
     * 合批产生的新批概要。
     */
    public record MergedChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            Instant createdAt
    ) {
    }
}
