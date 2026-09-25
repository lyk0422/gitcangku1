package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 新建赛事请求。
 *
 * @param raceId      赛事ID，全局唯一
 * @param baseStartMs 赛事基准（枪声）起跑时刻，Unix 毫秒时间戳（UTC）；
 *                    可空表示创建时不设基准，此类赛事不能登记波次
 * @param requestId   全局唯一请求ID（幂等键）
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        @Positive Long baseStartMs,
        @NotBlank String requestId
) {

    /** 兼容入口：不设置基准起跑时刻的赛事（此类赛事不能登记波次）。 */
    public CreateRaceRequest(String raceId, String requestId) {
        this(raceId, null, requestId);
    }
}
