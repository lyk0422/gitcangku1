package com.example.starter.water;

/**
 * 统一错误响应体。
 *
 * @param code    可区分的错误码，如 INVALID_ARGUMENT / NOT_FOUND / STATE_CONFLICT / ACTOR_MISMATCH / QUOTA_EXCEEDED
 * @param message 错误描述
 */
public record ErrorResponse(String code, String message) {
}
