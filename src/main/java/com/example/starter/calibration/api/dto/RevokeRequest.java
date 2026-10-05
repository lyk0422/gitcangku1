package com.example.starter.calibration.api.dto;

/**
 * 撤销证书请求（可选请求体）。
 *
 * @param requestId       幂等请求键；提供时启用去重，同键同参重放首次响应，异参 409
 * @param expectedVersion 期望版本，仅作为幂等身份的一部分参与结构化比较
 */
public record RevokeRequest(String requestId, Long expectedVersion) {
}
