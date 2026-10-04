package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 定时航线审查不可变结果记录。
 *
 * @param reviewId           定时审查记录唯一标识
 * @param routeId            航线标识
 * @param routeVersion       审查时的航线版本
 * @param airspaceVersion    审查时的空域版本（原禁飞区域版本）
 * @param operator           提交定时计划的操作者
 * @param conclusion         FEASIBLE / BLOCKED
 * @param hitZoneIds         命中 zoneId（字典序去重）
 * @param pointsSnapshot     审查时冻结的航点坐标快照
 * @param arrivalTimesEpoch  审查时冻结的各航点到达时刻（UTC epoch 整秒，严格递增）
 * @param requestId          提交定时审查的请求标识
 * @param createdAt          创建时间（epoch 毫秒，UTC）
 */
public record TimedReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                            String operator, String conclusion, List<String> hitZoneIds,
                            List<Point> pointsSnapshot, List<Long> arrivalTimesEpoch,
                            String requestId, long createdAt) {
}