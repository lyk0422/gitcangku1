package com.example.starter.water.dto;

/**
 * 窗口当前可用容量视图。
 *
 * @param windowId          窗口内部 ID
 * @param plannedVolume     计划水量（立方米）
 * @param activeLimitVolume 当前生效限供水量；无限供时为 null
 * @param effectiveVolume   当前可用总量：无限供为计划水量，有限供为限供水量
 * @param approvedVolume    已批准占用水量合计
 * @param availableVolume   剩余可用水量 = effectiveVolume - approvedVolume
 */
public record CapacityView(
        long windowId,
        String plannedVolume,
        String activeLimitVolume,
        String effectiveVolume,
        String approvedVolume,
        String availableVolume) {
}
