package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事当前有效裁定汇总响应。
 *
 * @param raceId  赛事ID
 * @param version 查询时赛事版本
 * @param rulings 全部当前有效裁定（按 pairId 字典序）
 */
public record PursuitRulingsResponse(
        String raceId,
        int version,
        List<RulingResponse> rulings
) {
}
