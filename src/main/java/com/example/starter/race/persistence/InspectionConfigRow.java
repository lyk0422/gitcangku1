package com.example.starter.race.persistence;

/**
 * race_inspection_config 表行记录。
 *
 * @param raceId             赛事ID
 * @param inspectionRequired 是否强制检录
 * @param validMinutes       PASS 有效分钟数（1~1440）
 * @param createdAt          配置写入时间，Unix毫秒时间戳
 */
public record InspectionConfigRow(
        String raceId,
        boolean inspectionRequired,
        int validMinutes,
        long createdAt
) {
}
