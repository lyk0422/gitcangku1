package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 受控拆批响应。父批状态落定 SPLIT、版本号 +1，父批区间冻结为历史；
 * 子批初始 QUARANTINED，数量按区间总长度生成。splitAt 为 UTC instant。
 */
public record SerialSplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        int parentVersion,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 受控拆批产生的子批概要：quantity 等于其区间总长度，version 初始为 1。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            BatchStatus status,
            long quantity,
            List<SerialIntervalView> serialIntervals,
            int version,
            Instant createdAt
    ) {
    }
}
