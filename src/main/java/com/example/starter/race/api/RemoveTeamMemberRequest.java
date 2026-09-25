package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 移除队伍成员请求；仅名单未锁定（OPEN）时可用。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RemoveTeamMemberRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
