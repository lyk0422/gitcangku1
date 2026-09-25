package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 读数单位换算留痕（只读）：提交单位与设备登记单位不同时记录，不产生额外读数条目。
 *
 * @param conversionId      换算记录自增主键
 * @param equipmentId       所属设备标识
 * @param readingId         关联读数标识
 * @param revisionNo        换算产生的读数修订号（1 为初始登记）
 * @param sourceUnit        提交方声明的原始单位
 * @param sourceValue       提交方提供的原始值（原始单位）
 * @param convertedValue    换算为设备登记单位后的值（与读数存储值一致）
 * @param convertedMinutes  换算分钟数（四舍五入到最近整数），参与单调性比较
 * @param requestId         产生该换算的请求 requestId
 * @param createdAt         换算记录登记时刻（UTC）
 */
public record ConversionRecord(
        long conversionId,
        String equipmentId,
        String readingId,
        int revisionNo,
        MeasurementUnit sourceUnit,
        BigDecimal sourceValue,
        BigDecimal convertedValue,
        long convertedMinutes,
        String requestId,
        Instant createdAt) {
}
