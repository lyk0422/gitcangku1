package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划实际状态响应：计划状态、版本与每条占用的实际状态及进出轨迹时刻。
 */
public record PlanActualResponse(
        String scheduleKey,
        String status,
        int version,
        List<OccupancyActualView> occupancies) {
}
