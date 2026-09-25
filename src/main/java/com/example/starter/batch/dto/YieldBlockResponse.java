package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 产率召回阻断原因查询结果。blocked 为 true 时，recalledBatchKey 为阻断来源
 * （批次自身被直接召回，或血缘闭包内最近的被召回祖先），reason/recalledAt 为该召回记录。
 */
public record YieldBlockResponse(
        String batchKey,
        boolean blocked,
        String recalledBatchKey,
        String reason,
        Instant recalledAt
) {
}
