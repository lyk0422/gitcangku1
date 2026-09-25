package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * @param reviewId        审核记录唯一标识（不可变）
 * @param routeId         航线标识
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED / STALE
 * @param reasonCode      审查原因：CLEAR / ZONE_HIT / EMERGENCY_EXCEPTION
 * @param hitZoneIds      BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param hitClosureIds   紧急例外通过的关闭窗口 id（字典序），否则为空列表
 * @param pointsSnapshot  审核时不可变的航点快照
 * @param current         仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 */
public record ReviewResultDto(
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String conclusion,
        String reasonCode,
        List<String> hitZoneIds,
        List<String> hitClosureIds,
        List<RoutePointDto> pointsSnapshot,
        Boolean current) {
}
