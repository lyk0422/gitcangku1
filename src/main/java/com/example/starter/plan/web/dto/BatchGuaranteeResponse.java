package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量保障改签响应：每项给出改签前后始发时刻、平移分钟数与新版本。
 */
public record BatchGuaranteeResponse(List<ItemResult> items) {

    /**
     * 单个计划的改签结果。
     */
    public record ItemResult(String scheduleKey, int version,
                             Instant oldDepartureUtc, Instant newDepartureUtc,
                             long shiftMinutes, List<OccupancyView> occupancies) {
    }
}