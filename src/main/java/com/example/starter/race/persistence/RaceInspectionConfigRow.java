package com.example.starter.race.persistence;

/**
 * race_inspection_config 表行记录。
 *
 * @param raceId       所属赛事ID
 * @param mandatory    是否强制器材检录
 * @param validMinutes 检录PASS有效分钟数（1~1440）
 * @param createdAt    配置时间，Unix毫秒时间戳
 * @param updatedAt    最近配置变更时间，Unix毫秒时间戳
 */
public record RaceInspectionConfigRow(
        String raceId,
        boolean mandatory,
        int validMinutes,
        long createdAt,
        long updatedAt
) {
}
