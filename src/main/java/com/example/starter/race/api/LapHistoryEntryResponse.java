package com.example.starter.race.api;

import com.example.starter.race.domain.LapPoint;

/**
 * 圈次更正历史条目：一条被替换的原始读数。
 *
 * @param lapPassId     被替换的原记录ID
 * @param replacedBy    替换后的当前记录ID
 * @param lapNo         圈号，从1开始
 * @param point         圈内点：A / B / FINISH
 * @param elapsedMillis 被替换前的原始读数（毫秒，非负）
 * @param createdBy     原记录提交人
 * @param createdAt     原记录提交时间，Unix毫秒时间戳
 * @param correctedBy   更正操作人
 * @param correctedAt   更正时间，Unix毫秒时间戳
 */
public record LapHistoryEntryResponse(
        String lapPassId,
        String replacedBy,
        int lapNo,
        LapPoint point,
        long elapsedMillis,
        String createdBy,
        long createdAt,
        String correctedBy,
        long correctedAt
) {
}