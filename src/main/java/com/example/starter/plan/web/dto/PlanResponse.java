package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、编组属性与占用清单（取消后历史占用仍原样返回）。
 * consist 为空表示该计划不做站台适配；occupancies 中含编组时带 platformId。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           ConsistView consist, List<OccupancyView> occupancies) {
}
