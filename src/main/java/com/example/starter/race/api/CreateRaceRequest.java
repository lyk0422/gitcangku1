package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建赛事请求。
 *
 * @param raceId    赛事ID，全局唯一
 * @param requestId 全局唯一请求ID（幂等键）
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        @NotBlank String requestId
) {
}
