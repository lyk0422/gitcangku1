package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单与危险品编组（取消后历史数据仍原样返回）。
 * cars 按车厢位置升序；计划未登记编组时为空列表。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies, List<CarView> cars) {
}
