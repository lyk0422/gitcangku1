package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 团队成绩榜响应：OPEN 实时计算；SEALED 返回封榜快照中的团队成绩。
 *
 * @param raceId   赛事ID
 * @param version  榜单对应的赛事版本号
 * @param status   OPEN / SEALED
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param teams    按展示顺序排列的团队成绩条目；无团队或封榜前无团队时为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamStandingsResponse(
        String raceId,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<TeamStandingEntryResponse> teams
) {
}
