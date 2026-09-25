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
 * @param hitZoneIds      BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param pointsSnapshot  审核时不可变的航点快照
 * @param priority        本次审查声明的备降优先级（NORMAL / EMERGENCY）
 * @param eventNo         EMERGENCY 的事件编号；NORMAL 为 null
 * @param bucketKey       规范化时空桶键；未声明时空段为 null
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
        String priority,
        String eventNo,
        String bucketKey,
        Boolean current) {
}
