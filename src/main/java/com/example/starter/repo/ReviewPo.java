package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.WeatherViolation;

import java.util.List;

/**
 * 审核不可变结果记录。快照固化标准、预测与值班版本，后续修订不改写。
 *
 * @param reviewId          审核记录唯一标识
 * @param routeId           航线标识
 * @param routeVersion      审核时的航线版本
 * @param airspaceVersion   审核时的空域版本
 * @param weatherVersion    审核时固化的气象标准版本
 * @param forecastVersion   审核时固化的预测气象版本；null 表示未涉及预测
 * @param dutyVersion       审核时固化的值班版本
 * @param conclusion        CLEAR / BLOCKED / WEATHER_BLOCKED
 * @param hitZoneIds        命中绝对禁飞区 zoneId（字典序去重）
 * @param weatherViolations 气象不达标明细（无时为空列表）
 * @param weatherSnapshot   审核时固化的相关标准与预测明细 JSON
 * @param pointsSnapshot    审核时航点不可变快照
 * @param requestId         提交审核的请求标识
 * @param createdAt         创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       long weatherVersion, Long forecastVersion, long dutyVersion,
                       String conclusion, List<String> hitZoneIds,
                       List<WeatherViolation> weatherViolations, String weatherSnapshot,
                       List<Point> pointsSnapshot,
                       String requestId, long createdAt) {
}
