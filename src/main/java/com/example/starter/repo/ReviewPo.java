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
 * @param dutyVersion     审核时固化的气象标准值班版本（无气象配置时为 0）
 * @param forecastVersion 审核时固化的预测气象版本；null 表示审核时不存在任何预测版本
 * @param weatherSnapshot 审核时固化的气象标准与预测快照 JSON；null 表示路径不经过任何气象区域
 * @param requestId       提交审核的请求标识
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, List<Point> pointsSnapshot,
                       long dutyVersion, Long forecastVersion, String weatherSnapshot,
                       String requestId, long createdAt) {
}