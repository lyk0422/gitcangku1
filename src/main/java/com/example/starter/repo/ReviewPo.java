package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.ZoneWindow;

import java.util.List;

/**
 * 审核不可变结果记录。
 *
 * @param reviewId         审核记录唯一标识
 * @param routeId          航线标识
 * @param routeVersion     审核时的航线版本
 * @param airspaceVersion  审核时的空域版本
 * @param conclusion       CLEAR / BLOCKED
 * @param hitZoneIds       命中 zoneId（字典序去重）
 * @param pointsSnapshot   审核时航点不可变快照
 * @param routeWindowStart 审核时航线飞行窗口起始快照（UTC epoch 毫秒，左闭）；与 routeWindowEnd 均 null 表示全时
 * @param routeWindowEnd   审核时航线飞行窗口结束快照（UTC epoch 毫秒，右开）
 * @param zoneWindows      审核时全部有效禁飞区的窗口不可变快照
 * @param requestId        提交审核的请求标识
 * @param createdAt        创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, List<Point> pointsSnapshot,
                       Long routeWindowStart, Long routeWindowEnd, List<ZoneWindow> zoneWindows,
                       String requestId, long createdAt) {
}
