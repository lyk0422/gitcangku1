package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 激活后单条替代映射视图：旧计划与其切换前占用、替代计划与其切换后占用。
 * 旧计划状态为 SUSPENDED（beforeOccupancies 为其被保留的历史占用），
 * 替代计划状态为 PUBLISHED（afterOccupancies 为切换后生效占用）。
 */
public record SwitchMappingView(String oldScheduleKey, int oldVersion, String oldStatus,
                                String replacementScheduleKey, int replacementVersion,
                                String replacementStatus,
                                List<OccupancyView> beforeOccupancies,
                                List<OccupancyView> afterOccupancies) {
}
