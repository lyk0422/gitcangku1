package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 工时表更换链节点视图。
 *
 * @param equipmentId      所属设备标识
 * @param meterKey         工时表唯一标识
 * @param status           ACTIVE / CLOSED
 * @param seqNo            链内序号，初始表为 0
 * @param predecessorKey   前驱表 meterKey，初始表为 null
 * @param replacementKey   产生/关闭本表的更换请求 replacementKey
 * @param initialRawHours  本表初始原始读数（小时）
 * @param finalRawHours    关表最终原始读数（小时），ACTIVE 时为 null
 * @param offsetHours      冻结的虚拟工时偏移（小时）
 * @param recalcVersion    重算版本号，初始 1，随跨表重算递增
 * @param createdAt        本表产生时刻（UTC）
 * @param closedAt         本表关闭时刻（UTC），ACTIVE 时为 null
 */
public record MeterResponse(
        String equipmentId,
        String meterKey,
        String status,
        int seqNo,
        String predecessorKey,
        String replacementKey,
        BigDecimal initialRawHours,
        BigDecimal finalRawHours,
        BigDecimal offsetHours,
        int recalcVersion,
        Instant createdAt,
        Instant closedAt) {
}
