package com.example.starter.race.api;

import com.example.starter.race.domain.CategoryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 组别信息响应。
 *
 * @param raceId       赛事ID
 * @param categoryCode 组别代码
 * @param capacity     组别容量；不限容量为 null
 * @param status       组别状态：OPEN / SEALED
 * @param activeCount  当前在赛（ACTIVE）选手人数
 * @param createdAt    创建时间，Unix毫秒时间戳
 * @param sealedAt     封榜时间，Unix毫秒时间戳；未封榜为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryResponse(
        String raceId,
        String categoryCode,
        Integer capacity,
        CategoryStatus status,
        int activeCount,
        long createdAt,
        Long sealedAt
) {
}
