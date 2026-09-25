package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、乘务指派、风险门禁标记与占用清单
 * （取消后历史占用与乘务指派仍原样返回）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           String driverId, String conductorId, boolean riskBlocked,
                           List<OccupancyView> occupancies) {
}
