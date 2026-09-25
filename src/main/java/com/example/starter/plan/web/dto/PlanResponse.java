package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、车底登记信息与占用清单（取消后历史占用仍原样返回）。
 *
 * @param rearrangePending 待重排标记；true 表示该已发布段因同车底交路中间段取消而待人工重排
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           String stockNo, String originStation, String destinationStation,
                           boolean rearrangePending, List<OccupancyView> occupancies) {
}
