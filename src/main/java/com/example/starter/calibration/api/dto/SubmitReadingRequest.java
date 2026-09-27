package com.example.starter.calibration.api.dto;

/**
 * 提交批次读数请求。读数为最多 6 位小数的十进制字符串。
 *
 * @param requestId   幂等键：同参重放返回首次结果，异参 409，失败不占键
 * @param measuredAt  测量时刻，ISO-8601（按 UTC 归一）
 * @param reading     原始读数，十进制字符串
 * @param submittedBy 读数提交人
 */
public record SubmitReadingRequest(
        String requestId,
        String measuredAt,
        String reading,
        String submittedBy) {
}
