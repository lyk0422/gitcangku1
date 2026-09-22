package com.example.starter.maintenance.model;

import java.time.Instant;

/**
 * 保养记录：保存锚点读数及其工时快照，不允许删除。
 *
 * @param maintenanceId           保养记录自增主键
 * @param anchorReadingId         锚点读数标识
 * @param anchorRevisionNo        完成保养时锚点读数的当前修订号
 * @param anchorSampledAt         锚点读数采样时刻（UTC）
 * @param anchorAccumulatedMinutes 完成保养时锚点读数累计工时快照，单位分钟
 * @param completedAt             保养完成时刻（UTC）
 */
public record MaintenanceRecord(long maintenanceId, String anchorReadingId, int anchorRevisionNo,
                                Instant anchorSampledAt, long anchorAccumulatedMinutes, Instant completedAt) {
}
