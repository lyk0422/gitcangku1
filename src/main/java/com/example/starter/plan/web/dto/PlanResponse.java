package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单与危险品编组（取消后历史占用与编组仍原样返回）。
 * hazardCars 按列车、位置升序；无危险品车厢时为空列表（不为 null）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies, List<HazardCarView> hazardCars) {
}
