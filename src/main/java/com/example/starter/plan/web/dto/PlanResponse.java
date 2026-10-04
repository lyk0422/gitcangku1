package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 日计划响应，含当前状态、版本与占用清单（取消后历史占用仍原样返回）。
 * tractionSnapshots 为发布时冻结的牵引快照（牵引方式与每段档案值、版本）；
 * 从未发布的草稿为空列表，后续档案合法变更不改历史快照。
 */
public record PlanResponse(String scheduleKey, LocalDate opDate, int version, String status,
                           List<OccupancyView> occupancies,
                           List<TractionSnapshotView> tractionSnapshots) {
}