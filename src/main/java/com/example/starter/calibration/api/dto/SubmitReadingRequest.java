package com.example.starter.calibration.api.dto;

/**
 * 提交批次读数请求。读数为最多 6 位小数的十进制字符串。
 *
 * @param readingKey  业务读数键，全局唯一（幂等键）
 * @param measuredAt  测量时刻，ISO-8601（按 UTC 归一）
 * @param reading     原始读数，十进制字符串
 * @param submittedBy 提交人
 * @param requestId   幂等请求 ID；同参重放返回首次结果，异参 409，失败不占键；可为 null 表示不启用
 */
public record SubmitReadingRequest(
        String readingKey,
        String measuredAt,
        String reading,
        String submittedBy,
        String requestId) {
}