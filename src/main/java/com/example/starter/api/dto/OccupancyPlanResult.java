package com.example.starter.api.dto;

import java.util.List;

/**
 * 穿越序列登记结果。
 *
 * @param routeId      航线标识
 * @param routeVersion 航线版本
 * @param items        登记后的有序穿越占用项
 */
public record OccupancyPlanResult(
        String routeId,
        Integer routeVersion,
        List<PlanSlotDto> items) {
}
