package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养更正附录（不可变）。完工后发现错误只能追加附录，不重开周期、不改写历史。
 *
 * @param appendixId    附录自增主键
 * @param maintenanceId 所属保养记录标识
 * @param equipmentId   所属设备标识
 * @param createdBy     附录创建人
 * @param content       更正内容说明
 * @param createdAt     创建时刻（UTC）
 */
public record MaintenanceAppendix(long appendixId, long maintenanceId, String equipmentId,
                                  String createdBy, String content, Instant createdAt) {
}
