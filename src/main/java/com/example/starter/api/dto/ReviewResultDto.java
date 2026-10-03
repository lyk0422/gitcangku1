package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * @param reviewId          审核记录唯一标识（不可变）
 * @param routeId           航线标识
 * @param routeVersion      审核时的航线版本
 * @param airspaceVersion   审核时的空域版本
 * @param conclusion        CLEAR / BLOCKED / WEATHER_BLOCKED / STALE
 * @param hitZoneIds        BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param pointsSnapshot    审核时不可变的航点快照
 * @param current           仅当前查询返回：当前航线/空域/气象是否仍与审核版本匹配
 * @param weatherVersion    审核时固化的气象版本；null 表示未提交气象版本
 * @param weatherViolations WEATHER_BLOCKED 时的违规明细（含实际值/要求值/差额）；
 *                          气象评估通过为空列表；未做气象评估为 null
 */
public record ReviewResultDto(
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> pointsSnapshot,
        Boolean current,
        Long weatherVersion,
        List<WeatherViolationDto> weatherViolations) {
}