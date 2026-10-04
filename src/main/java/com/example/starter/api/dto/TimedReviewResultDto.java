package com.example.starter.api.dto;

import java.util.List;

/**
 * 定时航线审查结果。历史查询保留原结论与冻结的坐标、时刻、空域版本；
 * 当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * @param reviewId        定时审查记录唯一标识（不可变）
 * @param routeId         航线标识
 * @param routeVersion    审查时的航线版本
 * @param airspaceVersion 审查时的空域版本（原禁飞区域版本）
 * @param operator        提交定时计划的操作者
 * @param conclusion      FEASIBLE / BLOCKED / STALE
 * @param hitZoneIds      BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param pointsSnapshot  审查时冻结的航点坐标快照（米，最多 3 位小数）
 * @param arrivalTimes    审查时冻结的各航点 UTC 整秒到达时刻（ISO-8601）
 * @param current         仅当前查询返回：当前航线/空域是否仍与审查版本匹配
 */
public record TimedReviewResultDto(
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String operator,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> pointsSnapshot,
        List<String> arrivalTimes,
        Boolean current) {
}