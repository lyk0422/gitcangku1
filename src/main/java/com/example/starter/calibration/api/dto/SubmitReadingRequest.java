package com.example.starter.calibration.api.dto;

/**
 * 提交读数请求。读数值为最多 6 位小数的十进制字符串。
 *
 * @param requestId   幂等请求 ID
 * @param readingKey  业务读数键，全局唯一
 * @param measuredAt  测量时刻，ISO-8601（按 UTC 归一），用于匹配证书
 * @param value       读数值，十进制字符串
 * @param submittedBy 读数提交人
 */
public record SubmitReadingRequest(
        String requestId,
        String readingKey,
        String measuredAt,
        String value,
        String submittedBy) {
}