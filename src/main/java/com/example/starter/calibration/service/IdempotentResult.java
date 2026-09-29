package com.example.starter.calibration.service;

/**
 * 幂等写操作结果：成功重放时 replay=true，body 为首次完整响应快照，httpStatus 为首次状态码。
 *
 * @param httpStatus 首次成功响应的 HTTP 状态码
 * @param body       响应体（重放时为反序列化的首次快照）
 * @param replay     是否为 requestId 同参重放
 */
public record IdempotentResult<T>(int httpStatus, T body, boolean replay) {

    static <T> IdempotentResult<T> first(int httpStatus, T body) {
        return new IdempotentResult<>(httpStatus, body, false);
    }

    static <T> IdempotentResult<T> replay(int httpStatus, T body) {
        return new IdempotentResult<>(httpStatus, body, true);
    }
}
