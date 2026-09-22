package com.example.starter.race.api;

/**
 * 检查点通过记录响应。
 *
 * @param timingId       全局唯一分段计时ID
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param checkpointCode 检查点编码
 * @param seq            检查点顺序，从1开始
 * @param elapsedMillis  分段耗时（毫秒）
 * @param createdAt      记录提交时间，Unix毫秒时间戳
 */
public record SplitTimeResponse(
        String timingId,
        String raceId,
        String bib,
        String checkpointCode,
        int seq,
        long elapsedMillis,
        long createdAt
) {
}
