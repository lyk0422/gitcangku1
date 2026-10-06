package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 寻径发布响应：一次创建并发布的日计划，冻结图版本、路径与逐段时刻。
 */
public record RoutePublishResponse(
        String scheduleKey,
        LocalDate opDate,
        int version,
        String status,
        int graphVersion,
        String trainNo,
        String fromStation,
        String toStation,
        Instant departUtc,
        Instant arrivalUtc,
        int totalMinutes,
        List<RouteLegView> legs) {
}
