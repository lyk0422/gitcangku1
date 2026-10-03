package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单（取消后历史占用仍原样返回）
 * 与发布时登记的乘务组（未发布或无乘务组时为空列表；历史发布保留当时人员名单）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies, List<String> crewMembers) {
}