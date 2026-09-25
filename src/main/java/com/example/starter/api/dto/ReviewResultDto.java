package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * @param reviewId          审核记录唯一标识（不可变）
 * @param routeId           航线标识
 * @param routeVersion      审核时的航线版本
 * @param airspaceVersion   审核时的空域版本
 * @param conclusion        CLEAR / BLOCKED / STALE
 * @param hitZoneIds        BLOCKED 时命中（二维相交的纯禁飞区）的全部 zoneId（字典序去重），否则为空列表
 * @param pointsSnapshot    审核时不可变的航点快照
 * @param cruiseAltitude    审核时航线巡航高度快照（米）
 * @param startUtc          审核时航线 UTC 起始时刻快照（含），epoch 毫秒
 * @param endUtc            审核时航线 UTC 结束时刻快照（不含），epoch 毫秒
 * @param verticalSeparation 二维相交区域逐高度带的垂直分离明细；无二维相交为空列表
 * @param current           仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 */
public record ReviewResultDto(
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> pointsSnapshot,
        Integer cruiseAltitude,
        Long startUtc,
        Long endUtc,
        List<VerticalZoneDto> verticalSeparation,
        Boolean current) {
}
