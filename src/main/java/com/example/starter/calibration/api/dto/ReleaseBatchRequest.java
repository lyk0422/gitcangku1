package com.example.starter.calibration.api.dto;

/**
 * 批次放行请求。放行人通过 X-Actor-Id 请求头提供。
 *
 * @param requestId 幂等请求 ID
 */
public record ReleaseBatchRequest(String requestId) {
}