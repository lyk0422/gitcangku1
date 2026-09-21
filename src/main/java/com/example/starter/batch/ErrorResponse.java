package com.example.starter.batch;

/**
 * 统一错误响应体。
 *
 * @param code    可区分的业务错误码
 * @param message 人类可读的错误描述
 */
public record ErrorResponse(String code, String message) {
}
