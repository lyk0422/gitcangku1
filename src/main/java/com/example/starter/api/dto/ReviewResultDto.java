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
 * @param current         仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 * @param cruiseAltitude  审核时巡航高度快照（米）；null 表示该航线未登记高度
 * @param startTime       审核时 UTC 时段起始快照，epoch 毫秒（含）；null 表示未登记
 * @param endTime         审核时 UTC 时段结束快照，epoch 毫秒（不含）；null 表示未登记
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
        Integer cruiseAltitude,
        Long startTime,
        Long endTime) {

    /** 兼容历史构造：不携带巡航高度与时段快照。 */
    public ReviewResultDto(String reviewId, String routeId, Integer routeVersion,
                           Long airspaceVersion, String conclusion, List<String> hitZoneIds,
                           List<RoutePointDto> pointsSnapshot, Boolean current) {
        this(reviewId, routeId, routeVersion, airspaceVersion, conclusion, hitZoneIds,
                pointsSnapshot, current, null, null, null);
    }
}
