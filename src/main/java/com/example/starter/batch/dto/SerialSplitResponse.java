package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 序号区间拆批响应。父批状态落定 SPLIT，其序号域仅作冻结历史；
 * 子批初始 QUARANTINED，quantity 为各自区间总长度。splitAt 为 UTC instant。
 */
public record SerialSplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分子批概要：intervals 为规范化后的子批区间集合，quantity 等于区间总长度。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            int quantity,
            List<IntervalView> intervals,
            BatchStatus status
    ) {
    }
}
