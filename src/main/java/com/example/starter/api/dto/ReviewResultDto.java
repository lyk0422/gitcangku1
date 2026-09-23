package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * <p>结果内保存审核时刻双方窗口快照：航线整体飞行窗口与每个命中区域的有效窗口，
 * 历史不会随窗口修改而改变；既有历史缺少窗口时按全时解释（起止为 null）。</p>
 *
 * @param reviewId        审核记录唯一标识（不可变）
 * @param routeId         航线标识
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED / STALE
 * @param hitZoneIds      BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param hits            BLOCKED 时命中项快照（zoneId 与区域窗口，字典序），否则为空列表
 * @param pointsSnapshot  审核时不可变的航点快照
 * @param routeWindowStart 审核时航线整体飞行窗口开始时刻，epoch 毫秒（UTC），区间含；null 表示全时
 * @param routeWindowEnd   审核时航线整体飞行窗口结束时刻，epoch 毫秒（UTC），区间不含；null 表示全时
 * @param current         仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 */
public record ReviewResultDto(
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String conclusion,
        List<String> hitZoneIds,
        List<ZoneHitDto> hits,
        List<RoutePointDto> pointsSnapshot,
        Long routeWindowStart,
        Long routeWindowEnd,
        Boolean current) {
}
