package com.example.starter.calibration.api.dto;

/**
 * 创建参考漂移档案请求。写操作使用全局 requestId 幂等。
 *
 * @param requestId       全局请求 ID（幂等键），非空
 * @param operator        操作者，非空
 * @param instrumentId    仪器 ID
 * @param profiledAt      参考测量时刻，ISO-8601（按 UTC 归一，毫秒精度）
 * @param referenceOffset 参考偏移，最多 6 位小数的十进制字符串
 * @param expectedVersion 期望版本（可选，参与幂等身份指纹）
 */
public record CreateDriftProfileRequest(
        String requestId,
        String operator,
        String instrumentId,
        String profiledAt,
        String referenceOffset,
        Long expectedVersion) {
}
