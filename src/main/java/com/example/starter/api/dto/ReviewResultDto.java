package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 *
 * <p>链路字段（coverageUnion/linkGaps/maxContinuousLossMs）仅在被审航线版本
 * 登记了通信档案时存在；旧无通信档案航线不适用，字段为 null。</p>
 *
 * @param reviewId             审核记录唯一标识（不可变）
 * @param routeId              航线标识
 * @param routeVersion         审核时的航线版本
 * @param airspaceVersion      审核时的空域版本
 * @param conclusion           CLEAR / BLOCKED / LINK_BLOCKED / STALE
 * @param hitZoneIds           BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param pointsSnapshot       审核时不可变的航点快照
 * @param current              仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 * @param coverageUnion        审核时各航段合并覆盖并集（不可变存档），无档案为 null
 * @param linkGaps             审核时全部连续失联段（含跨段缺口），无档案为 null
 * @param maxContinuousLossMs  审核时最长连续失联毫秒（精确小数字符串），无档案为 null
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
        List<SegmentUnionDto> coverageUnion,
        List<LinkGapDto> linkGaps,
        String maxContinuousLossMs) {
}
