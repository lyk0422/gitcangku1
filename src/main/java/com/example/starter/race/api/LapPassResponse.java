package com.example.starter.race.api;

/**
 * 单个选手在某圈某点位的当前通过明细。
 *
 * @param lapNumber     圈号
 * @param point         圈内点位：A/B/FINISH
 * @param elapsedMillis 当前读数（毫秒）
 * @param timingId      当前记录ID
 */
public record LapPassResponse(
        int lapNumber,
        String point,
        long elapsedMillis,
        String timingId
) {
}