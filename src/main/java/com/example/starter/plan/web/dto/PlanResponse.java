package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、继承等级与占用清单（取消/被抢占后历史占用仍原样返回）。
 * planLevel 为发布时继承的全部占用区段最高等级，草稿为 null。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           Integer planLevel, List<OccupancyView> occupancies) {
}
