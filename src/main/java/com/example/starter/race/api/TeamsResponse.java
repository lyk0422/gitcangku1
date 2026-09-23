package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 团队榜响应：OPEN 时为实时派生成绩，SEALED 后为封榜只读快照。
 *
 * @param raceId   赛事ID
 * @param version  团队榜对应的赛事版本号（封榜后与个人快照相同）
 * @param status   OPEN / SEALED
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param teams    按展示顺序排列的团队成绩；无团队或赛事封榜前无团队时为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamsResponse(
        String raceId,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<TeamResponse> teams
) {
}
