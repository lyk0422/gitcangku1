package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单组别成绩榜响应：组别未封榜时为实时计算，已封榜时为只读快照。
 *
 * @param raceId       赛事ID
 * @param categoryCode 组别代码
 * @param version      榜单对应的赛事版本号
 * @param sealed       该榜单是否为封榜快照
 * @param sealedAt     组别封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param entries      按展示顺序排列的组内成绩条目（不含已退赛选手）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryStandingResponse(
        String raceId,
        String categoryCode,
        int version,
        boolean sealed,
        Long sealedAt,
        List<ResultEntryResponse> entries
) {
}