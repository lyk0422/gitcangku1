package com.example.starter.race.api;

import java.util.List;

/**
 * 中立赛段配置响应。
 *
 * @param raceId  赛事ID
 * @param version 配置成功后的赛事版本号（查询时为当前版本）
 * @param legs    按起点顺序排列的中立区间；未启用中立模式为空列表
 */
public record NeutralLegsConfigResponse(
        String raceId,
        int version,
        List<NeutralLegResponse> legs
) {
}
