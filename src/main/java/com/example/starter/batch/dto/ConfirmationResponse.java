package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionStatus;
import com.example.starter.batch.StorageRole;

import java.time.Instant;

/**
 * 偏差处置确认响应：携带确认序号、确认角色及确认后的偏差状态（第 2 次确认后 RESOLVED）。
 */
public record ConfirmationResponse(
        String excursionKey,
        String actorId,
        StorageRole role,
        int limitVersion,
        int sequence,
        ExcursionStatus excursionStatus,
        Instant confirmedAt
) {
}
