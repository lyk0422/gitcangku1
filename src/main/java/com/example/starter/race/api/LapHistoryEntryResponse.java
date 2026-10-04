package com.example.starter.race.api;

/**
 * 圈次更正历史条目：一次更正追加一条，原读数保留。
 *
 * @param timingId      被更正的原记录ID
 * @param lapNumber     圈号
 * @param point         圈内点位：A/B/FINISH
 * @param elapsedMillis 原读数（毫秒）
 * @param replacedBy    更正后的新当前记录ID
 * @param replacedAt    更正时间，Unix毫秒时间戳
 */
public record LapHistoryEntryResponse(
        String timingId,
        int lapNumber,
        String point,
        long elapsedMillis,
        String replacedBy,
        long replacedAt
) {
}