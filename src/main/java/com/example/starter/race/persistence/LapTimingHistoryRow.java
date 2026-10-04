package com.example.starter.race.persistence;

import com.example.starter.race.domain.LapPoint;

/**
 * lap_timing_history 表行记录（更正追加的历史读数，原读数不被覆盖）。
 *
 * @param historyId     自增主键
 * @param timingId      被更正的原记录ID
 * @param raceId        所属赛事ID
 * @param bib           选手参赛号
 * @param lapNumber     圈号
 * @param point         圈内点位：A/B/FINISH
 * @param elapsedMillis 原读数（毫秒）
 * @param replacedBy    更正后的新当前记录ID
 * @param replacedAt    更正时间，Unix毫秒时间戳
 */
public record LapTimingHistoryRow(
        long historyId,
        String timingId,
        String raceId,
        String bib,
        int lapNumber,
        LapPoint point,
        long elapsedMillis,
        String replacedBy,
        long replacedAt
) {
}