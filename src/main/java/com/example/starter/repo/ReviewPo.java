package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 审核不可变结果记录。
 *
 * @param reviewId        审核记录唯一标识
 * @param routeId         航线标识
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED
 * @param hitZoneIds      命中 zoneId（二维相交的纯禁飞区，字典序去重）
 * @param pointsSnapshot  审核时航点不可变快照
 * @param cruiseAltitude  审核时巡航高度快照（米）
 * @param startUtc        审核时 UTC 起始时刻快照（含），epoch 毫秒
 * @param endUtc          审核时 UTC 结束时刻快照（不含），epoch 毫秒
 * @param verticalDetail  二维相交区域逐高度带垂直分离明细 JSON 快照
 * @param requestId       提交审核的请求标识
 * @param createdAt       创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, List<Point> pointsSnapshot,
                       int cruiseAltitude, long startUtc, long endUtc,
                       String verticalDetail, String requestId, long createdAt) {
}
