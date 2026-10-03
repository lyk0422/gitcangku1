package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核时效诊断（只读）：保留审核时固化的版本快照与当前版本的对照，
 * 并给出失效原因；不改变任何状态。
 *
 * @param reviewId               审核记录标识
 * @param routeId                航线标识
 * @param conclusion             保存时的原结论（永不改写）
 * @param current                审核时固化的全部版本是否仍与当前一致
 * @param staleReasons           失效原因列表（为空表示仍有效）：ROUTE_VERSION_CHANGED /
 *                               AIRSPACE_VERSION_CHANGED / WEATHER_DUTY_VERSION_CHANGED /
 *                               FORECAST_VERSION_CHANGED
 * @param frozenRouteVersion     审核时固化的航线版本
 * @param currentRouteVersion    当前航线版本
 * @param frozenAirspaceVersion  审核时固化的空域版本
 * @param currentAirspaceVersion 当前空域版本
 * @param frozenDutyVersion      审核时固化的气象值班版本
 * @param currentDutyVersion     当前气象值班版本
 * @param frozenForecastVersion  审核时固化的预测气象版本；null 表示无预测
 * @param latestForecastVersion  当前最新预测气象版本；null 表示尚无预测
 * @param weatherSnapshot        审核时固化的气象标准与预测快照 JSON；null 表示路径不经过气象区域
 */
public record ReviewDiagnosticsDto(
        String reviewId,
        String routeId,
        String conclusion,
        boolean current,
        List<String> staleReasons,
        int frozenRouteVersion,
        Integer currentRouteVersion,
        long frozenAirspaceVersion,
        long currentAirspaceVersion,
        long frozenDutyVersion,
        long currentDutyVersion,
        Long frozenForecastVersion,
        Long latestForecastVersion,
        Object weatherSnapshot) {
}