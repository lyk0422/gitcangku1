package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、车底交路登记与占用清单（取消后历史占用仍原样返回）。
 * stockKey/originStation/destStation 为 null 表示该计划不参与车底交路链。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           String stockKey, String originStation, String destStation,
                           String chainState, List<OccupancyView> occupancies) {
}
