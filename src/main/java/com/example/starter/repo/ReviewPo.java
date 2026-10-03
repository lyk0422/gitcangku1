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
 * @param conclusion      CLEAR / BLOCKED / WEATHER_BLOCKED
 * @param hitZoneIds      命中 zoneId（字典序去重）
 * @param pointsSnapshot  审核时航点不可变快照
 * @param requestId       提交审核的请求标识
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 * @param weatherVersion  审核时固化的气象（值班）版本；null 表示未提交气象版本
 * @param weatherSnapshot 审核时固化的气象标准与预测快照 JSON；null 表示无气象评估
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, List<Point> pointsSnapshot,
                       String requestId, long createdAt,
                       Long weatherVersion, String weatherSnapshot) {
}