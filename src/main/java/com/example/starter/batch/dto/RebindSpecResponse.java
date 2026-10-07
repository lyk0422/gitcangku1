package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 规范换版响应。换版后旧向量保留但不再作为放行依据；
 * 若新版本尚无 PASS 向量，批次状态回退 QUARANTINED。
 */
public record RebindSpecResponse(
        String batchKey,
        String specKey,
        int previousVersion,
        int newVersion,
        BatchStatus batchStatus,
        String operator,
        Instant createdAt
) {
}
