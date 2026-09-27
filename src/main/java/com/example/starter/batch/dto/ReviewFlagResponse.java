package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 召回联动待复核标记；血缘召回命中托盘任一批次时，同托盘所有未装运批次各产生一条。
 */
public record ReviewFlagResponse(
        String palletKey,
        String batchKey,
        String sourceRecalledKey,
        Instant createdAt
) {
}
