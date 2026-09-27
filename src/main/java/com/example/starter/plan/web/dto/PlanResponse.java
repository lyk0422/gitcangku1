package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单与危险品编组（取消后历史占用/编组仍原样返回）。
 * consist 按车厢位置升序；hazardClass 为 null 表示普通车厢。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies,
                           List<ConsistCarView> consist) {
}
