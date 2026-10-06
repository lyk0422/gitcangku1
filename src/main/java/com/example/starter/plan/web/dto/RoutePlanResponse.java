package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 寻径发布响应：一次创建成功的 PUBLISHED 日计划，冻结图版本、路径与各段时刻。
 */
public record RoutePlanResponse(String scheduleKey, int graphVersion, LocalDate opDate,
                                String status, String trainNo, String fromStation, String toStation,
                                Instant departUtc, Instant arriveUtc, int totalMinutes,
                                List<RouteLegView> legs) {
}
