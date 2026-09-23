package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 预览中的单个相交计划：计划摘要、当前版本与全部占用（占用保留历史，不按时隙裁剪）。
 */
public record SwitchPlanView(String scheduleKey, LocalDate opDate, int version, String status,
                             List<OccupancyView> occupancies) {
}
