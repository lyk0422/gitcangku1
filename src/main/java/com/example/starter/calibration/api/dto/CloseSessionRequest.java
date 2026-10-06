package com.example.starter.calibration.api.dto;

/**
 * 关闭会话请求。关闭后冻结全部方向与精确结果，不再允许追加。
 *
 * @param requestId              幂等请求 ID
 * @param expectedSessionVersion 期望会话版本，须等于当前版本
 */
public record CloseSessionRequest(
        String requestId,
        Long expectedSessionVersion) {
}
