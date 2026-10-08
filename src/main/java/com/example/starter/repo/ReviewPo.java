package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.QuadraticCurve;
import com.example.starter.domain.RouteKind;

import java.util.List;

/**
 * 审核不可变结果记录。
 *
 * @param reviewId        审核记录唯一标识
 * @param routeId         航线标识
 * @param routeKind       审核时航线几何类型（LINE/CURVE）
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED
 * @param hitZoneIds      命中 zoneId（字典序去重）
 * @param pointsSnapshot  LINE 类型审核时航点不可变快照；CURVE 类型为空列表
 * @param curveSnapshot   CURVE 类型审核时全部曲线段完整控制点不可变快照；LINE 为空列表
 * @param requestId       提交审核的请求标识
 * @param operator        提交审核的操作者标识
 * @param createdAt       创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, RouteKind routeKind,
                       int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds,
                       List<Point> pointsSnapshot, List<QuadraticCurve> curveSnapshot,
                       String requestId, String operator, long createdAt) {
}
