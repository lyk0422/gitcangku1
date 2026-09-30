package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应：两个父批合并产生一个新批次；父批置为 MERGED 不再可用。
 */
public record MergeResponse(
        String mergeKey,
        List<String> parentBatchKeys,
        String childBatchKey,
        String batchNo,
        String productCode,
        BatchStatus childStatus,
        List<String> requiredTests,
        Instant mergedAt
) {
}
