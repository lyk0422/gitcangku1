package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 组别信息响应。
 *
 * @param categoryCode 组别代码
 * @param capacity     组别容量（在赛人数上限）
 * @param sealed       组别是否已封榜
 * @param createdAt    配置时间，Unix毫秒时间戳
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryResponse(
        String categoryCode,
        int capacity,
        boolean sealed,
        long createdAt,
        Long sealedAt
) {
}