package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionStatus;
import com.example.starter.batch.ExcursionType;
import com.example.starter.batch.StorageRole;

import java.time.Instant;

/**
 * 时刻查询中的偏差条目：含类型、缺测/越界区间、限值版本、处置状态与处置人。
 * confirmations 按确认序号排列；未解除偏差的批次门禁为 BLOCKED。
 */
public record ExcursionEntry(
        String excursionKey,
        ExcursionType eventType,
        int limitVersion,
        Instant from,
        Instant to,
        String detail,
        ExcursionStatus status,
        Instant resolvedAt,
        java.util.List<ConfirmationEntry> confirmations
) {

    /**
     * 偏差处置确认条目。
     */
    public record ConfirmationEntry(
            String actorId,
            StorageRole role,
            int limitVersion,
            int sequence,
            Instant confirmedAt
    ) {
    }
}
