package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养模板版本（设备内版本化，每个版本不可变）。
 *
 * @param equipmentId               所属设备标识
 * @param templateVersion           模板版本号，从 1 开始递增
 * @param overdueThresholdMinutes   OVERDUE 阈值（分钟）：本轮运行分钟满足 [周期, 阈值) 为 DUE，达到阈值为 OVERDUE
 * @param createdAt                 模板版本创建时刻（UTC）
 */
public record MaintenanceTemplate(
        String equipmentId,
        int templateVersion,
        long overdueThresholdMinutes,
        Instant createdAt) {
}
