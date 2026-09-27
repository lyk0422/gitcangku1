package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单与登记站停（取消后历史数据仍原样返回）。
 * stationStops 按计划内序号升序，时刻为 null 时稳定输出 null（始发/终到语义）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies,
                           List<StationStopView> stationStops) {
}
