package com.example.starter.calibration.api.dto;

/**
 * 创建后续测量修订请求。仅已放行批次可创建；创建后批次重新开放，旧复核引用失效（STALE）。
 *
 * @param requestId 幂等请求 ID；同参重放首次结果，异参 409，失败不占键
 */
public record CreateRevisionRequest(String requestId) {
}