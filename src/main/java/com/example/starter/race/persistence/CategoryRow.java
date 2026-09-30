package com.example.starter.race.persistence;

/**
 * race_category 表行记录。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码，赛事内唯一
 * @param capacity     组别在册容量（人数）；null 表示不限容量
 * @param createdAt    创建时间，Unix毫秒时间戳
 */
public record CategoryRow(
        String raceId,
        String categoryCode,
        Integer capacity,
        long createdAt
) {
}
