package com.example.starter.repo;

import com.example.starter.domain.CurveSegment;
import com.example.starter.domain.Point;
import com.example.starter.domain.RouteKind;

import java.util.List;

/**
 * 审核不可变结果记录。
 *
 * @param reviewId         审核记录唯一标识
 * @param routeId          被审核航线标识
 * @param routeVersion     审核时的航线版本
 * @param airspaceVersion  审核时的空域版本
 * @param conclusion       CLEAR / BLOCKED
 * @param hitZoneIds       命中 zoneId（字典序去重）
 * @param kind             审核时航线的几何类型
 * @param pointsSnapshot   LINE 类型审核时航点不可变快照；QUADRATIC 为空列表
 * @param segmentsSnapshot QUADRATIC 类型审核时完整曲线段（含全部控制点）不可变快照
 * @param requestId        提交审核的请求标识
 * @param createdAt        创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, RouteKind kind,
                       List<Point> pointsSnapshot, List<CurveSegment> segmentsSnapshot,
                       String requestId, long createdAt) {
}
