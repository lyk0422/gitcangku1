package com.example.starter.race.persistence;

import com.example.starter.race.domain.LapPassView;
import com.example.starter.race.domain.LapPoint;

/**
 * lap_pass 表行记录：绕圈赛事某选手某圈某点的当前通过记录（每点仅一条）。
 *
 * @param lapPassId     全局唯一圈次记录ID（第二层幂等键）
 * @param raceId        所属赛事ID
 * @param bib           选手参赛号
 * @param lapNo         圈号，从1开始
 * @param point         圈内点：A/B/FINISH
 * @param elapsedMillis 自起跑开始的累计耗时（毫秒，非负整数，0~86400000）
 * @param createdBy     提交人（操作者标识）
 * @param createdAt     提交时间，Unix毫秒时间戳
 */
public record LapPassRow(
        String lapPassId,
        String raceId,
        String bib,
        int lapNo,
        LapPoint point,
        long elapsedMillis,
        String createdBy,
        long createdAt
) implements LapPassView {
}