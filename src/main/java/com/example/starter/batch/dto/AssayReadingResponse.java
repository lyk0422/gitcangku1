package com.example.starter.batch.dto;

import com.example.starter.batch.AssayRoundStatus;
import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 写读数响应。value 为原值字符串不舍入；roundStatus 为写入后轮次状态，
 * 第三条读数写入即封结并给出判定；mean 为封结时的算术均值显示值（最多 10 位小数，
 * 仅用于展示，判定使用原始和精确比较，不舍入）；未封结时 mean 为 null。
 */
public record AssayReadingResponse(
        long roundId,
        String batchKey,
        String testItem,
        int roundNo,
        int seq,
        String value,
        String operator,
        int version,
        AssayRoundStatus roundStatus,
        String mean,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
