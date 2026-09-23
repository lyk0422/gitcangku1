package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

/**
 * 保养项目重算视图：按修正后的当前累计工时一次性重算的 DUE/NOT_DUE 状态与下一阈值。
 *
 * @param itemKey                保养项目键（PERIODIC 周期保养项目）
 * @param status                 DUE 或 NOT_DUE
 * @param runHours               本轮运行工时（最新工时减最近保养锚点工时，小时）
 * @param nextThresholdHours     下一保养阈值工时（小时）
 * @param lastAnchorReadingId    最近完成保养的锚点读数标识（无保养时 null）
 * @param lastAnchorCumulativeHours 最近保养锚点工时（小时；无保养为 0）
 */
public record DriftMaintenanceItemView(
        String itemKey,
        String status,
        BigDecimal runHours,
        BigDecimal nextThresholdHours,
        String lastAnchorReadingId,
        BigDecimal lastAnchorCumulativeHours) {
}
