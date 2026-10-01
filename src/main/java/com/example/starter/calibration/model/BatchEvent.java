package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次事件（表 batch_event）。离群检测、复核、裁决、统计重算、修订与放行的不可变记录，
 * 按事件 ID 升序即提交顺序；后续操作不得改写历史事件。
 *
 * @param id            事件 ID（自增）
 * @param batchId       所属批次 ID
 * @param eventType     事件类型
 * @param outlierId     关联离群记录 ID；与离群无关的事件为 null
 * @param readingId     关联读数 ID；无关联读数时为 null
 * @param actor         操作角色（复核人、放行人等）；系统检测事件为 null
 * @param revision      事件发生时批次测量修订版本
 * @param targetValue   封存目标值；非检测事件为 null
 * @param tolerance     封存允许偏差；非检测事件为 null
 * @param readingValue  封存原始读数；非检测事件为 null
 * @param computedValue 封存计算值；非检测事件为 null
 * @param decision      复核或裁决结论 ACCEPT/REJECT；无关事件为 null
 * @param reason        复核理由；无则为 null
 * @param details       诊断明细（如重算后统计、STALE 原因）；无则为 null
 * @param createdAt     事件时间（UTC）
 */
public record BatchEvent(
        long id,
        long batchId,
        BatchEventType eventType,
        Long outlierId,
        Long readingId,
        String actor,
        Integer revision,
        BigDecimal targetValue,
        BigDecimal tolerance,
        BigDecimal readingValue,
        BigDecimal computedValue,
        ReviewDecision decision,
        String reason,
        String details,
        Instant createdAt) {
}