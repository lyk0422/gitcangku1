package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单与中间站登记（取消后历史原样返回）。
 * stationStops 按 seq 升序，元素中到发为 null 时 JSON 字段也为 null（始发/终到语义）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies,
                           List<StationStopView> stationStops) {
}
