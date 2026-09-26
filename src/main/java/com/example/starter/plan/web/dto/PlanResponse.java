package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本与占用清单（取消后历史占用仍原样返回）。
 * rearrangement 为该计划最近一次气象限速重排记录；从未重排时为 null。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies, RearrangementView rearrangement) {
}
