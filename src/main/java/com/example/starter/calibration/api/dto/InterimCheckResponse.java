package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 期间核查响应。
 *
 * @param id            核查记录 ID
 * @param checkKey      核查键
 * @param instrumentId  仪器 ID
 * @param checkedAt     核查时刻（UTC）
 * @param standardValue 标准值（十进制字符串）
 * @param measuredValue 实测值（十进制字符串）
 * @param tolerance     容差（十进制字符串）
 * @param deviation     |标准值-实测值|（十进制字符串，BigDecimal 精确计算）
 * @param verdict       核查判定：PASS / FAIL
 * @param checkedBy     核查人
 * @param requestId     请求幂等键
 * @param replayed      是否为 requestId 同参重放（true 表示返回首次结果，未再次生效）
 * @param createdAt     记录创建时间（UTC）
 */
public record InterimCheckResponse(
        long id,
        String checkKey,
        String instrumentId,
        Instant checkedAt,
        String standardValue,
        String measuredValue,
        String tolerance,
        String deviation,
        String verdict,
        String checkedBy,
        String requestId,
        boolean replayed,
        Instant createdAt) {
}
