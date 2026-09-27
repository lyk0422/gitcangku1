package com.example.starter.calibration.api.dto;

/**
 * 提交批次读数请求。
 *
 * @param requestId   调用方请求 ID（幂等键）
 * @param readingKey  业务读数键，批次内唯一
 * @param value       读数值，最多 6 位小数的十进制字符串
 * @param submittedBy 读数提交人
 */
public record SubmitReadingRequest(
        String requestId,
        String readingKey,
        String value,
        String submittedBy) {
}
