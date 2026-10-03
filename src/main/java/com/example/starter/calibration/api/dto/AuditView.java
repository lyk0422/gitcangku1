package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 离群审计视图（不可变记录）。null 表示该事件不涉及对应值。
 *
 * @param id               审计记录 ID（即排序依据）
 * @param eventType        事件类型
 * @param outlierId        关联离群记录 ID；批次级事件为 null
 * @param batchRevision    事件发生时的测量修订版本
 * @param targetValue      封存目标值（十进制字符串）；无则为 null
 * @param allowedDeviation 封存允许偏差（十进制字符串）；无则为 null
 * @param readingValue     封存读数值（十进制字符串）；无则为 null
 * @param actor            操作角色；无则为 null
 * @param decision         相关结论；无则为 null
 * @param detail           诊断说明；无则为 null
 * @param createdAt        事件时间（UTC）
 */
public record AuditView(
        long id,
        String eventType,
        Long outlierId,
        int batchRevision,
        String targetValue,
        String allowedDeviation,
        String readingValue,
        String actor,
        String decision,
        String detail,
        Instant createdAt) {
}