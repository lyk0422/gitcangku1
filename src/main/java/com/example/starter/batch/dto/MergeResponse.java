package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。目标批次初始 QUARANTINED，继承全部来源必做检验项并集；
 * 成分版本 v1 为来源过敏原代码并集与最高隔离级别；来源批次置为 MERGED 退出可用库存。
 */
public record MergeResponse(
        String targetBatchKey,
        BatchStatus status,
        String containerKey,
        List<String> sourceBatchKeys,
        List<String> allergenCodes,
        String segregationLevel,
        Instant createdAt
) {
}
