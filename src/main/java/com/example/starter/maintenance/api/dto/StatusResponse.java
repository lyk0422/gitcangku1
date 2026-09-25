package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 设备保养状态视图：本轮运行分钟 = 最新读数累计工时 - 最近保养锚点工时（无保养时从 0 计算）。
 * 判定一律使用换算后分钟数；displayUnit 仅控制展示层换算（同一 BigDecimal 规则），不改变存储口径。
 *
 * @param equipmentId                            设备唯一标识
 * @param version                                设备当前版本号
 * @param unit                                   设备登记单位（存储口径）
 * @param maintenancePeriod                      保养周期（按登记单位）
 * @param maintenancePeriodMinutes               保养周期（换算后分钟数）
 * @param latestSampledAt                        最新读数采样时刻（无读数时为 null）
 * @param latestCumulativeValue                  最新读数累计工时（按登记单位，无读数时为 0）
 * @param latestCumulativeMinutes                最新读数累计工时（换算后分钟数，无读数时为 0）
 * @param lastMaintenanceAnchorSampledAt         最近保养锚点时刻（无保养时为 null）
 * @param lastMaintenanceAnchorCumulativeValue   最近保养锚点工时快照（按登记单位，无保养时为 0）
 * @param lastMaintenanceAnchorCumulativeMinutes 最近保养锚点工时快照（换算后分钟数，无保养时为 0）
 * @param runMinutes                             本轮运行分钟（判定口径）
 * @param status                                 OK 或 DUE（runMinutes 达到保养周期即 DUE）
 * @param displayUnit                            展示单位（查询参数指定，缺省为设备登记单位）
 * @param displayRunValue                        本轮运行工时（按展示单位，由分钟数单次换算）
 * @param displayPeriodValue                     保养周期阈值（按展示单位，由分钟数单次换算）
 */
public record StatusResponse(
        String equipmentId,
        long version,
        String unit,
        BigDecimal maintenancePeriod,
        long maintenancePeriodMinutes,
        Instant latestSampledAt,
        BigDecimal latestCumulativeValue,
        long latestCumulativeMinutes,
        Instant lastMaintenanceAnchorSampledAt,
        BigDecimal lastMaintenanceAnchorCumulativeValue,
        long lastMaintenanceAnchorCumulativeMinutes,
        long runMinutes,
        String status,
        String displayUnit,
        BigDecimal displayRunValue,
        BigDecimal displayPeriodValue) {
}
