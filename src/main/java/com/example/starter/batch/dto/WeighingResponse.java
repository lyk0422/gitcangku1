package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 称量记录响应。massG 单位克；supersedesId 非空表示本记录为更正记录；
 * version 为写入落定后的批次称量链版本（称量记录总数）。createdAt 为 UTC instant。
 */
public record WeighingResponse(
        long weighingId,
        String batchKey,
        String ingredient,
        String massG,
        Long supersedesId,
        long version,
        Instant createdAt
) {
}
