package com.example.starter.race.persistence;

import com.example.starter.race.domain.RaceStatus;

/**
 * race_category 表行记录。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码，赛事内唯一
 * @param capacity     组别容量：在赛（未退赛）选手数上限
 * @param status       组别状态：OPEN-开放可迁入，SEALED-已封榜冻结
 * @param createdAt    创建时间，Unix毫秒时间戳
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 */
public record CategoryRow(
        String raceId,
        String categoryCode,
        int capacity,
        RaceStatus status,
        long createdAt,
        Long sealedAt
) {
}