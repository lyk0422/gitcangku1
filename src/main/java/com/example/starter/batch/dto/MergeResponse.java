package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。父批全部置为 MERGED 并退出可用集合；新批初始 QUARANTINED，
 * 产品编码与必做检验项与父批相同，生产 UTC 时间取父批最晚值，不继承检验或批准记录。
 * mergedAt 为 UTC instant。
 */
public record MergeResponse(
        String batchKey,
        String batchNo,
        String productCode,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        List<String> parentBatchKeys,
        BatchStatus parentStatus,
        Instant mergedAt
) {
}
