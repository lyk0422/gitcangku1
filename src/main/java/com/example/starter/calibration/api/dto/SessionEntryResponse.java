package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 会话条目响应。correctedValue 为未舍入精确值（原值×斜率+方向偏移）；
 * displayValue 为 HALF_UP 保留 4 位的显示值，不参与合格判定。
 *
 * @param id             测量记录 ID
 * @param measurementKey 测量键（会话键#序号）
 * @param seq            会话内序号（1 起）
 * @param direction      读数变化方向：UP / DOWN
 * @param rawValue       原始读数（十进制字符串）
 * @param correctedValue 未舍入校正值（十进制字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING / RELEASED
 * @param submittedBy    提交人
 * @param createdAt      追加时间（UTC）
 */
public record SessionEntryResponse(
        long id,
        String measurementKey,
        int seq,
        String direction,
        String rawValue,
        String correctedValue,
        String displayValue,
        boolean passed,
        String status,
        String submittedBy,
        Instant createdAt) {
}
