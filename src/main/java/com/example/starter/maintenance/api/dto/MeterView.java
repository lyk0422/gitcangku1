package com.example.starter.maintenance.api.dto;

/**
 * 工时表视图（更换链环节）。
 *
 * @param meterKey         工时表唯一标识
 * @param chainSeq         链内序号，初始表为 0
 * @param status           ACTIVE 或 CLOSED
 * @param initialRawHours  新表起始原始读数（分钟）
 * @param finalRawHours    关闭时申报的最终原始读数（分钟）；ACTIVE 表为 null
 * @param offsetHours      虚拟工时偏移（分钟）：virtual = raw + offset
 * @param baseVirtualHours 基准虚拟工时（分钟）= initialRawHours + offsetHours
 * @param lastRawHours     该表最后有效原始读数（分钟）；无读数时为 null
 * @param lastVirtualHours 该表最后有效虚拟工时（分钟）；无读数时为 null
 */
public record MeterView(
        String meterKey,
        int chainSeq,
        String status,
        long initialRawHours,
        Long finalRawHours,
        long offsetHours,
        long baseVirtualHours,
        Long lastRawHours,
        Long lastVirtualHours) {
}
