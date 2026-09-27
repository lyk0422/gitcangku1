package com.example.starter.race.api;

/**
 * 终点计时事件不可变记录响应。
 *
 * @param finishTimingId 终点计时事件ID
 * @param raceId         所属赛事ID
 * @param bib            选手参赛号
 * @param occurredAt     事件发生的绝对UTC毫秒时间戳
 * @param finishTimeMs   原始完赛耗时（毫秒）
 * @param createdAt      记录提交的绝对UTC毫秒时间戳
 */
public record FinishTimingResponse(
        String finishTimingId,
        String raceId,
        String bib,
        long occurredAt,
        long finishTimeMs,
        long createdAt
) {
}
