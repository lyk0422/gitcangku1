package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本、占用清单、站台风险标记、编组与风险快照
 * （取消后历史占用仍原样返回；未登记编组时 consist 为 null，无风险时 riskSnapshot 为 null）。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           boolean platformRisk, List<OccupancyView> occupancies,
                           ConsistView consist, RiskSnapshotView riskSnapshot) {
}
