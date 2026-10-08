package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * @param reviewId        审核记录唯一标识（不可变）
 * @param routeId         航线标识
 * @param routeKind       审核时航线几何类型：LINE 直线；CURVE 二次曲线
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED / STALE
 * @param hitZoneIds      BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param pointsSnapshot  LINE 类型审核时不可变航点快照；CURVE 类型为空列表
 * @param curveSnapshot   CURVE 类型审核时不可变的完整曲线段控制点快照（含 P0/P1/P2）；LINE 为空列表
 * @param operator        提交审核的操作者标识
 * @param current         仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 */
public record ReviewResultDto(
        String reviewId,
        String routeId,
        String routeKind,
        Integer routeVersion,
        Long airspaceVersion,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> pointsSnapshot,
        List<CurveSegmentDto> curveSnapshot,
        String operator,
        Boolean current) {
}
