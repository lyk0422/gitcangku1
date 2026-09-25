package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数单位换算留痕视图（只读）。提交单位与设备登记单位不同时产生，不产生额外读数条目。
 *
 * @param conversionId        换算记录标识
 * @param equipmentId         所属设备标识
 * @param readingId           换算后写入的读数标识
 * @param revisionNo          该换算产生的读数修订号
 * @param submittedUnit       提交时附带的单位标签
 * @param submittedValue      提交的原始值（按提交单位）
 * @param convertedValue      换算后存储值（按设备登记单位）
 * @param convertedMinutes    换算后分钟数（判定口径）
 * @param equalizedByRounding 换算误差导致与相邻读数相等（视为非递减放行）
 * @param requestId           产生该换算的请求 requestId
 * @param createdAt           换算记录时刻（UTC）
 */
public record ConversionRecordView(
        long conversionId,
        String equipmentId,
        String readingId,
        int revisionNo,
        String submittedUnit,
        BigDecimal submittedValue,
        BigDecimal convertedValue,
        long convertedMinutes,
        boolean equalizedByRounding,
        String requestId,
        Instant createdAt) {
}
