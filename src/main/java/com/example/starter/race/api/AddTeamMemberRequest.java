package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新增队伍成员请求；仅名单未锁定（OPEN）时可用。
 *
 * @param bib             参赛者号码，须为本赛事已报名选手且未加入其他队伍
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record AddTeamMemberRequest(
        @NotBlank String bib,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
