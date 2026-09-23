package com.example.starter.api.dto;

import com.example.starter.domain.ZoneWindow;

import java.util.List;

/**
 * 审核结果。历史查询保留原结论；当前查询在版本不再匹配时 conclusion 为 STALE。
 * 窗口快照为审核时刻的不可变记录；既有历史缺少窗口时字段为 null，按全时解释。
 *
 * @param reviewId         审核记录唯一标识（不可变）
 * @param routeId          航线标识
 * @param routeVersion     审核时的航线版本
 * @param airspaceVersion  审核时的空域版本
 * @param conclusion       CLEAR / BLOCKED / STALE
 * @param hitZoneIds       BLOCKED 时命中的全部 zoneId（字典序去重），否则为空列表
 * @param pointsSnapshot   审核时不可变的航点快照
 * @param routeWindowStart 审核时航线飞行窗口起始快照（UTC epoch 毫秒，左闭）；与 routeWindowEnd 均 null 表示全时
 * @param routeWindowEnd   审核时航线飞行窗口结束快照（UTC epoch 毫秒，右开）
 * @param zoneWindows      审核时全部有效禁飞区的窗口快照
 * @param current          仅当前查询返回：当前航线/空域是否仍与审核版本匹配
 */
public record ReviewResultDto(
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        String conclusion,
        List<String> hitZoneIds,
        List<RoutePointDto> pointsSnapshot,
        Long routeWindowStart,
        Long routeWindowEnd,
        List<ZoneWindow> zoneWindows,
        Boolean current) {
}
