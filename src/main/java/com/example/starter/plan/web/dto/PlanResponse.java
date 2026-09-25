package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单与编组信息（取消后历史占用仍原样返回）。
 * version 即编组版本：占用整体替换或编组变更成功一次加一。
 * consistLength/cars/platformCodes 未登记编组时分别为 null 与空列表；
 * platformRisk 为 true 表示存在未解除的站台超长风险（PLATFORM_RISK）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies, Integer consistLength,
                           List<String> cars, List<String> platformCodes, boolean platformRisk) {
}
