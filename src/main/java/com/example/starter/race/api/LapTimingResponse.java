package com.example.starter.race.api;

/**
 * 圈次点位通过记录响应。
 *
 * @param timingId      全局唯一圈次记录ID
 * @param bib           选手参赛号
 * @param lapNumber     圈号
 * @param point         圈内点位：A/B/FINISH
 * @param elapsedMillis 自起跑开始的累计耗时（毫秒）
 * @param createdAt     提交时间，Unix毫秒时间戳
 */
public record LapTimingResponse(
        String timingId,
        String bib,
        int lapNumber,
        String point,
        long elapsedMillis,
        long createdAt
) {
}