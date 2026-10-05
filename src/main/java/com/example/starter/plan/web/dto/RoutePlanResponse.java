package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 寻径发布响应：一次创建的 PUBLISHED 日计划及其冻结的图版本、路径与逐段时刻。
 */
public record RoutePlanResponse(
        String scheduleKey,
        LocalDate opDate,
        int version,
        String status,
        int graphVersion,
        String trainNo,
        String fromStation,
        String toStation,
        Instant departUtc,
        Instant arriveUtc,
        int totalMinutes,
        List<RouteSegmentView> segments) {
}
