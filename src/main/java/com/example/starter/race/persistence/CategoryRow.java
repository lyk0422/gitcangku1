package com.example.starter.race.persistence;

/**
 * race_category 表行记录。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码，赛事内唯一
 * @param capacity     组别容量（在赛人数上限）；退赛者不占容量
 * @param sealed       组别是否已封榜
 * @param createdAt    配置时间，Unix毫秒时间戳
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 */
public record CategoryRow(
        String raceId,
        String categoryCode,
        int capacity,
        boolean sealed,
        long createdAt,
        Long sealedAt
) {
}