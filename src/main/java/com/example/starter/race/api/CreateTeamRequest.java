package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建队伍请求；队长须为本赛事已报名选手。
 *
 * @param teamId          队伍ID，同一赛事内唯一
 * @param captainBib      队长参赛号
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record CreateTeamRequest(
        @NotBlank String teamId,
        @NotBlank String captainBib,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
