package com.example.starter.repo;

import com.example.starter.domain.TimedPoint;

import java.util.List;

/**
 * 定时航线可行性审查不可变结果记录。
 *
 * @param reviewId        审查记录唯一标识
 * @param routeId         引用的航线标识
 * @param routeVersion    审查时核对的航线版本
 * @param airspaceVersion 审查时冻结的空域版本
 * @param operator        操作者标识
 * @param conclusion      CLEAR / BLOCKED
 * @param hitZoneIds      命中 zoneId（字典序去重）
 * @param pointsSnapshot  审查时冻结的航点与到达时刻快照（坐标单位米，时刻为 UTC 整秒）
 * @param requestId       提交审查的请求标识
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 */
public record TimedReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                            String operator, String conclusion, List<String> hitZoneIds,
                            List<TimedPoint> pointsSnapshot, String requestId, long createdAt) {
}