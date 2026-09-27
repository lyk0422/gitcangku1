package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、区段占用清单与中间站到发登记
 * （取消后历史占用与登记仍原样返回，顺序按提交时序号）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies, List<StationDwellView> stationDwells) {
}
