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
 * @param hits            命中项快照（zoneId 与审核时刻区域有效窗口，字典序）
 * @param pointsSnapshot  审核时航点不可变快照
 * @param routeWindow     审核时航线整体飞行窗口快照（全时为 {@link TimeWindow#ALL_TIME}）
 * @param requestId       提交审核的请求标识
 * @param createdAt       创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, List<ZoneHitPo> hits,
                       List<Point> pointsSnapshot, TimeWindow routeWindow,
                       String requestId, long createdAt) {
}
