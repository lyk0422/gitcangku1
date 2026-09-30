package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间、必做检验项、规范化过敏原集合与当前清洁代次，不继承检验或批准记录。
 * splitAt 为 UTC instant。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分产生的子批概要。allergens/cleaningGeneration 为继承自父批的过敏原集合与清洁代次。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            List<String> allergens,
            int cleaningGeneration,
            Instant createdAt
    ) {
    }
}
