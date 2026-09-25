package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 登记一支接力队伍：按固定棒次给出每棒选手，棒次连续、同队同棒次仅一人。
 *
 * @param teamKey         队伍标识，同一接力赛事内唯一
 * @param members         各棒次选手（棒次1..棒次数各一人，顺序固定）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RegisterRelayTeamRequest(
        @NotBlank String teamKey,
        @NotEmpty @Valid List<RelayMemberRequest> members,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
