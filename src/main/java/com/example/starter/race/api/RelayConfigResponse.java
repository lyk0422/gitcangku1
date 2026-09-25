package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 接力配置/登记后的赛事信息。
 *
 * @param raceId         赛事ID
 * @param version        当前版本号
 * @param status         OPEN / SEALED
 * @param legCount       棒次数；未配置接力为 null
 * @param handoffLimitMs 交接区用时上限（毫秒）；未配置接力为 null
 * @param createdAt      创建时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayConfigResponse(
        String raceId,
        int version,
        String status,
        Integer legCount,
        Integer handoffLimitMs,
        long createdAt) {
}
