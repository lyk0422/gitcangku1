package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 移除队伍成员请求；仅未锁定且赛事 OPEN 时可用，队长不可移除。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RemoveTeamMemberRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
