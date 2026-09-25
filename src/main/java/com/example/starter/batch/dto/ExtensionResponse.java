package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 延期记录响应。status 为 PENDING_CONFIRM（待确认）或 EFFECTIVE（已生效，终态不可改写）；
 * confirmedBy/confirmedRole/confirmedAt/newExpiresAt 仅在生效后有值。
 */
public record ExtensionResponse(
        String batchKey,
        String extensionKey,
        String retestConclusion,
        int extensionMinutes,
        String retester,
        String status,
        Instant submittedAt,
        String confirmedBy,
        String confirmedRole,
        Instant confirmedAt,
        Instant newExpiresAt
) {
}
