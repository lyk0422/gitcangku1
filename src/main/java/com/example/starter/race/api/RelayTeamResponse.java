package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 接力队伍登记结果。
 *
 * @param teamKey         队伍标识
 * @param members         按棒次顺序排列的选手
 * @param version         登记后的赛事版本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayTeamResponse(
        String teamKey,
        List<RelayMemberResponse> members,
        int version) {
}
