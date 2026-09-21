package com.example.starter.evidence.domain;

import java.time.LocalDateTime;

/**
 * 交接历史行，只追加；decidedAt 在 PENDING 时为 null。
 */
public record Transfer(
        Long id,
        Long evidenceId,
        String fromCustodianId,
        String toCustodianId,
        TransferStatus status,
        LocalDateTime createdAt,
        LocalDateTime decidedAt
) {
}
