package com.example.starter.calibration.api.dto;

/**
 * 创建参考漂移档案请求。referencedAt 须为毫秒精度的 ISO-8601（按 UTC 归一），offset 最多 6 位小数。
 *
 * @param instrumentId    仪器 ID
 * @param referencedAt    参考测量时刻（UTC 毫秒），ISO-8601
 * @param offset          参考偏移，十进制字符串，最多 6 位小数
 * @param operator        操作者（可选）；提供 requestId 时必填
 * @param requestId       全局请求 ID（可选）；提供时启用幂等去重
 * @param expectedVersion 期望版本（可选），仅作为幂等身份组成部分
 */
public record CreateDriftArchiveRequest(
        String instrumentId,
        String referencedAt,
        String offset,
        String operator,
        String requestId,
        String expectedVersion) {
}
