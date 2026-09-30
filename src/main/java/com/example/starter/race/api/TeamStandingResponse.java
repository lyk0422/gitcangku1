package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 团队成绩榜响应：OPEN 时按各队当前锁定版本实时汇总，SEALED 时返回封榜只读快照。
 *
 * @param raceId 赛事ID
 * @param version 榜单对应的赛事版本号
 * @param status OPEN / SEALED
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param teams 按团队总分升序排列的团队成绩（同分按团队ID字典序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamStandingResponse(
        String raceId,
        int version,
        com.example.starter.race.domain.RaceStatus status,
        Long sealedAt,
        List<TeamScoreResponse> teams
) {
}
