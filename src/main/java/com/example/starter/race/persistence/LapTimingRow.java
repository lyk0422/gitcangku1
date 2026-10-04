package com.example.starter.race.persistence;

import com.example.starter.race.domain.LapPoint;
import com.example.starter.race.domain.LapTimingView;

/**
 * lap_timing 表行记录（选手某圈某点位的当前通过记录；每点只保留一条）。
 *
 * @param timingId      全局唯一圈次记录ID（第二层幂等键；更正后换新ID）
 * @param raceId        所属赛事ID
 * @param bib           选手参赛号
 * @param lapNumber     圈号，从1到赛事总圈数
 * @param point         圈内点位：A/B/FINISH
 * @param elapsedMillis 自起跑开始的累计耗时（毫秒，非负整数）
 * @param createdAt     记录提交时间，Unix毫秒时间戳
 */
public record LapTimingRow(
        String timingId,
        String raceId,
        String bib,
        int lapNumber,
        LapPoint point,
        long elapsedMillis,
        long createdAt
) implements LapTimingView {
}