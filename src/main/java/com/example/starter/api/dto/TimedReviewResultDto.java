package com.example.starter.api.dto;

import java.util.List;

/**
 * 定时航线可行性审查结果。历史查询保留冻结的坐标、时刻与原结论；
 * 当前查询在航线或空域版本不再匹配时 conclusion 为 STALE。
 *
 * @param reviewId        审查记录唯一标识（不可变）
 * @param routeId         引用的航线标识
 * @param routeVersion    审查时核对的航线版本
 * @param airspaceVersion 审查时冻结的空域版本
 * @param operator        操作者标识
 * @param conclusion      CLEAR / BLOCKED / STALE
 * @param hitZoneIds      BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param points          审查时冻结的航点与到达时刻快照
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
        List<TimedPointDto> points,
        Boolean current) {
}