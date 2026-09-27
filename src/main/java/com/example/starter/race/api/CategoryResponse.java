package com.example.starter.race.api;

import com.example.starter.race.domain.CategoryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 组别信息响应。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码
 * @param capacity     组别容量（在赛人数上限）
 * @param activeCount  当前在赛（未退赛）人数
 * @param status       组别状态：OPEN / SEALED
 * @param createdAt    创建时间，Unix毫秒时间戳
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryResponse(
        String raceId,
        String categoryCode,
        int capacity,
        int activeCount,
        CategoryStatus status,
        long createdAt,
        Long sealedAt
) {
}
