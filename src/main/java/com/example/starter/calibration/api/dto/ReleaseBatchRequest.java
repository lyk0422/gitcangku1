package com.example.starter.calibration.api.dto;

/**
 * 批次放行请求。
 *
 * @param requestId 调用方请求 ID（幂等键）
 */
public record ReleaseBatchRequest(String requestId) {
}
