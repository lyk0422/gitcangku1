package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次事件响应（历史与诊断）。事件不可变，按事件 ID 升序即提交顺序；
 * 各封存字段仅在对应事件类型下有值，其余为 null。
 *
 * @param id            事件 ID
 * @param eventType     事件类型
 * @param outlierId     关联离群记录 ID；无关联为 null
 * @param readingId     关联读数 ID；无关联为 null
 * @param actor         操作角色；系统检测事件为 null
 * @param revision      事件发生时批次测量修订版本
 * @param targetValue   封存目标值（十进制字符串）；非检测事件为 null
 * @param tolerance     封存允许偏差（十进制字符串）；非检测事件为 null
 * @param readingValue  封存原始读数（十进制字符串）；非检测事件为 null
 * @param computedValue 封存计算值（十进制字符串）；非检测事件为 null
 * @param decision      复核或裁决结论；无关事件为 null
 * @param reason        复核理由；无则为 null
 * @param details       诊断明细；无则为 null
 * @param createdAt     事件时间（UTC）
 */
public record BatchEventResponse(
        long id,
        String eventType,
        Long outlierId,
        Long readingId,
        String actor,
        Integer revision,
        String targetValue,
        String tolerance,
        String readingValue,
        String computedValue,
        String decision,
        String reason,
        String details,
        Instant createdAt) {
}