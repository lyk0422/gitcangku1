package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。parentCount 为实际合入父批数（要求 2～5）；parents 按请求顺序；
 * 新批初始 QUARANTINED，绑定声明版本与匹配前缀已固化。
 */
public record MergeResponse(
        String batchKey,
        String batchNo,
        String productCode,
        BatchStatus status,
        int parentCount,
        List<String> parents,
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        List<String> requiredTests,
        Instant mergedAt
) {
}
