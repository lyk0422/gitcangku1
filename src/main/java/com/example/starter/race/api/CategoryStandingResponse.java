package com.example.starter.race.api;

import com.example.starter.race.domain.CategoryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 组别成绩榜响应：组别 OPEN 时为实时计算，SEALED 时为封榜只读快照。
 *
 * @param raceId       赛事ID
 * @param categoryCode 组别代码
 * @param version      榜单对应的赛事版本号
 * @param status       组别状态：OPEN / SEALED
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param entries      按展示顺序排列的成绩条目
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryStandingResponse(
        String raceId,
        String categoryCode,
        int version,
        CategoryStatus status,
        Long sealedAt,
        List<ResultEntryResponse> entries
) {
}
