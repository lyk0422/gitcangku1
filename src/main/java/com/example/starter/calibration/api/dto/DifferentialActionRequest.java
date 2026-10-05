package com.example.starter.calibration.api.dto;

/**
 * 差分放行/取消请求。
 *
 * @param requestId       客户端请求 ID（幂等键）
 * @param expectedVersion 期望的差分版本；非空时必须与当前版本一致，否则 409
 */
public record DifferentialActionRequest(
        String requestId,
        Long expectedVersion) {
}
