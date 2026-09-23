package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 切换快照中的单个计划视图：业务键、版本、当时状态与全部占用明细（历史占用保留）。
 */
public record DisruptionPlanSnapshotView(String scheduleKey, int version, String status,
                                         List<OccupancyView> occupancies) {
}
