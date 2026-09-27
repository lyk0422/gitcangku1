package com.example.starter.race.api;

import com.example.starter.race.domain.CategoryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个组别的成绩榜响应：OPEN 时实时计算，SEALED 时返回封榜快照内容。
 *
 * @param raceId       赛事ID
 * @param categoryCode 组别代码
 * @param status       组别状态：OPEN / SEALED
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param entries      按展示顺序排列的成绩条目（仅含当前所属该组别的在赛选手）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryStandingResponse(
        String raceId,
        String categoryCode,
        CategoryStatus status,
        Long sealedAt,
        List<ResultEntryResponse> entries
) {
}
