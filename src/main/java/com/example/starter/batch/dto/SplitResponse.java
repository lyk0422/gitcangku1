package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间与必做检验项，不继承隔离类别声明、检验或批准记录（segregationCategory 恒为 null）。
 * splitAt 为 UTC instant。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分产生的子批概要；segregationCategory 恒为 null，子批须自行声明后方可装载托盘。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            String segregationCategory,
            BatchStatus status,
            List<String> requiredTests,
            Instant createdAt
    ) {
    }
}
