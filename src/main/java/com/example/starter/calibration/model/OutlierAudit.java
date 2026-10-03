package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离群审计记录（不可变）。封存事件发生时的目标值、允许偏差、读数与操作角色；
 * 字段、排序与时间精度保持稳定，null 表示该事件不涉及对应值。
 *
 * @param id               审计记录 ID（自增，即排序依据）
 * @param batchId          所属批次 ID
 * @param outlierId        关联离群记录 ID，批次级事件为 null
 * @param eventType        事件类型
 * @param batchRevision    事件发生时的测量修订版本
 * @param targetValue      封存目标值，无则为 null
 * @param allowedDeviation 封存允许偏差，无则为 null
 * @param readingValue     封存读数值，无则为 null
 * @param actor            操作角色（提交人/复核人），无则为 null
 * @param decision         相关结论 ACCEPT/REJECT，无则为 null
 * @param detail           诊断说明，无则为 null
 * @param createdAt        事件时间（UTC）
 */
public record OutlierAudit(
        long id,
        long batchId,
        Long outlierId,
        AuditEventType eventType,
        int batchRevision,
        BigDecimal targetValue,
        BigDecimal allowedDeviation,
        BigDecimal readingValue,
        String actor,
        String decision,
        String detail,
        Instant createdAt) {
}