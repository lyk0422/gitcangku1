package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.TimeWindow;

import java.util.List;

/**
 * 审核不可变结果记录。
 *
 * @param reviewId        审核记录唯一标识
 * @param routeId         航线标识
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED
 * @param hitZoneIds      命中 zoneId（字典序去重）
 * @param pointsSnapshot  审核时航点不可变快照
 * @param routeWindow     审核时航线整体飞行窗口不可变快照（旧记录缺省按全时解释）
 * @param hitZoneWindows  各命中区域在审核时的有效窗口快照，顺序与 hitZoneIds 一致
 * @param requestId       提交审核的请求标识
 * @param createdAt       创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, List<Point> pointsSnapshot,
                       TimeWindow routeWindow, List<ZoneWindowSnapshot> hitZoneWindows,
                       String requestId, long createdAt) {

    /**
     * 命中区域窗口快照（不可变）。
     *
     * @param zoneId 命中区域标识
     * @param window 审核时该区域的有效窗口
     */
    public record ZoneWindowSnapshot(String zoneId, TimeWindow window) {
    }
}
