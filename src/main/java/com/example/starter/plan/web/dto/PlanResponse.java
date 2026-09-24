package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本与占用清单（取消后历史占用仍原样返回）。
 * overnight 标识夜间跨零点计划；nightPairKey 为所属计划对业务键，无则为 null。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           boolean overnight, String nightPairKey,
                           List<OccupancyView> occupancies) {
}
