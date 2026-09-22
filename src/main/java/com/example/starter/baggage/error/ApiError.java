package com.example.starter.baggage.error;

/**
 * 统一错误响应体。
 *
 * @param code    机器可读错误码
 * @param message 错误描述
 */
public record ApiError(String code, String message) {
}
