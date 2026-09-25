package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、继承的走廊等级与占用清单（取消/被抢占后历史占用仍原样返回）。
 *
 * @param corridorLevel 计划继承的走廊等级，即其全部占用区段登记等级的最大值（未登记按 1）
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           int corridorLevel, List<OccupancyView> occupancies) {
}
