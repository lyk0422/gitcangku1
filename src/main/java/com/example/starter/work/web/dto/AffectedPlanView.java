package com.example.starter.work.web.dto;

import com.example.starter.plan.web.dto.OccupancyView;
import java.time.LocalDate;
import java.util.List;

/**
 * 受施工窗口影响的已发布计划视图，携带与窗口相交的占用明细。
 */
public record AffectedPlanView(
        String scheduleKey,
        LocalDate opDate,
        String status,
        List<OccupancyView> conflicts) {
}
