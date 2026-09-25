package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、计划等级（全部占用区段的最高登记等级，未登记区段按 1 级）
 * 与占用清单（取消/被抢占后历史占用仍原样返回）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           int level, List<OccupancyView> occupancies) {
}
