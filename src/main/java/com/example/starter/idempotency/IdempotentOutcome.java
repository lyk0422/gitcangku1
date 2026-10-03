package com.example.starter.idempotency;

/**
 * 幂等执行结果。
 *
 * @param body 响应体（JSON）
 * @param httpStatus 响应状态码
 * @param replayed 是否为同参重放
 */
public record IdempotentOutcome(String body, int httpStatus, boolean replayed) {
}