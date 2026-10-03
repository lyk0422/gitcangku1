package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个组别的成绩榜响应：OPEN 实时计算；SEALED 返回组别封榜快照。
 *
 * @param raceId       所属赛事ID
 * @param categoryCode 组别代码
 * @param version      榜单对应的赛事版本号
 * @param status       组别状态：OPEN / SEALED
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param entries      组内成绩条目，按展示顺序排列
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryStandingResponse(
        String raceId,
        String categoryCode,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<ResultEntryResponse> entries
) {
}