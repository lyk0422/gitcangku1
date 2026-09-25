package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数换算分钟 - 最近保养锚点换算分钟（无保养时从 0 计算），
 * 判定统一使用换算后的分钟口径。displayXxx 字段为可选展示单位换算结果，
 * 由存储分钟数直接换算（同一 BigDecimal 规则），不改变存储口径。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param measurementUnit                        设备登记计量单位（MINUTES/HOURS）
 * @param maintenancePeriodValue                 保养周期（登记单位十进制）
 * @param maintenancePeriodMinutes               保养周期换算分钟数（判定阈值）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeValue                  最新读数累计工时（登记单位，无读数时为 0）
 * @param latestCumulativeMinutes                最新读数累计工时换算分钟数（无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeValue   最近保养锚点工时快照（登记单位，无保养时为 0）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时换算分钟数快照（无保养时为 0）
 * @param runValue                               本轮运行工时（登记单位十进制）
 * @param runMinutes                             本轮运行分钟（判定口径）
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期分钟数即 DUE）
 * @param displayUnit                            展示单位（请求参数 unit 指定，缺省为设备登记单位）
 * @param displayRunValue                        以展示单位表示的本轮运行工时（由存储分钟数直接换算）
 * @param displayMaintenancePeriodValue          以展示单位表示的保养周期阈值（由存储分钟数直接换算）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        String measurementUnit,
        BigDecimal maintenancePeriodValue,
        long maintenancePeriodMinutes,
        Instant latestSampledAt,
        BigDecimal latestCumulativeValue,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        BigDecimal lastMaintenanceAnchorCumulativeValue,
        long lastMaintenanceAnchorCumulativeMinutes,
        BigDecimal runValue,
        long runMinutes,
        String status,
        String displayUnit,
        BigDecimal displayRunValue,
        BigDecimal displayMaintenancePeriodValue) {
}
