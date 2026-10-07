package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 显式换版响应。previousVersion 为换版前绑定版本；换版后批次按新版本重新检验，
 * 若原处于 PENDING_RELEASE 将回退为 QUARANTINED。
 */
public record RebindResponse(
        String batchKey,
        String specKey,
        int previousVersion,
        int specVersion,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
