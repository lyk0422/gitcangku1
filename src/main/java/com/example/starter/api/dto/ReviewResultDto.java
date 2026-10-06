package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * @param reviewId        审核记录唯一标识（不可变）
 * @param routeId         航线标识
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED / ENERGY_BLOCKED / STALE
 * @param hitZoneIds      几何命中的全部 zoneId（字典序去重），未命中为空列表；
 *                        ENERGY_BLOCKED 时仍保留几何命中
 * @param pointsSnapshot  审核时不可变的航点快照
 * @param energyCheck     审核时冻结的能量审查快照；该航线版本无能量档案时为 null
 * @param current         仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 */
public record ReviewResultDto(
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> pointsSnapshot,
        EnergyCheckDto energyCheck,
        Boolean current) {
}
