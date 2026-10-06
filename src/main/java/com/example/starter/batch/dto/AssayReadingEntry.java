package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 平行测定读数明细（查询用）。value 为原值字符串，不舍入。
 */
public record AssayReadingEntry(
        int seq,
        String value,
        String operator,
        Instant createdAt
) {
}
