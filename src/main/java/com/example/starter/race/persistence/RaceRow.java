package com.example.starter.race.persistence;

import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ScoringMode;

/**
 * race 表行记录。
 *
 * @param raceId      赛事ID，全局唯一
 * @param version     版本号，从1开始
 * @param status      赛事状态
 * @param scoringMode 计分模式：STANDARD-标准计时，HANDICAP-让分精确分数
 * @param createdAt   创建时间，Unix毫秒时间戳
 */
public record RaceRow(
        String raceId,
        int version,
        RaceStatus status,
        ScoringMode scoringMode,
        long createdAt
) {
}
