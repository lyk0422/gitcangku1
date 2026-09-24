package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 期间核查历史条目（只读视图）。
 *
 * @param checkKey      核查业务键
 * @param instrumentId  仪器 ID
 * @param checkedAt     UTC 核查时刻
 * @param standardValue 标准值（十进制字符串）
 * @param actualValue   实测值（十进制字符串）
 * @param tolerance     容差（十进制字符串）
 * @param result        判定结果：PASS / FAIL
 * @param checkedBy     核查人
 * @param createdAt     记录提交时间（UTC）
 */
public record InterimCheckView(
        String checkKey,
        String instrumentId,
        Instant checkedAt,
        String standardValue,
        String actualValue,
        String tolerance,
        String result,
        String checkedBy,
        Instant createdAt) {
}
