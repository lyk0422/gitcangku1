package com.example.starter.calibration.api.dto;

/**
 * 撤销类写请求的可选请求体（证书/漂移档案撤销）。
 *
 * @param operator        操作者（可选）；提供 requestId 时必填
 * @param requestId       全局请求 ID（可选）；提供时启用幂等去重
 * @param expectedVersion 期望版本（可选），仅作为幂等身份组成部分
 */
public record RevokeRequest(String operator, String requestId, String expectedVersion) {
}
