package com.example.starter.race.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建赛事请求。
 *
 * @param requestId 全局唯一请求ID，用于幂等去重
 * @param raceId    赛事ID，全局唯一
 */
public record CreateRaceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String raceId) {
}
