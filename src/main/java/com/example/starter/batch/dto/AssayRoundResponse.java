package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 平行测定轮次响应：轮次参数、状态、已写入读数原值与封结判定结果。
 * mean/range 未封结时为 null；batchStatus 为该响应落定后的批次状态。
 */
public record AssayRoundResponse(
        String batchKey,
        String testItem,
        int roundNo,
        String status,
        int version,
        String meanMin,
        String meanMax,
        String maxRange,
        List<ReadingView> readings,
        String mean,
        String range,
        String batchStatus,
        Instant createdAt,
        Instant sealedAt
) {
    /**
     * 单条读数原值：序号 + 十进制字符串值，不回解析不回舍入。
     */
    public record ReadingView(int seq, String value) {
    }
}
