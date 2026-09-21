package com.example.starter.curtailment.error;

/**
 * 统一错误响应体。
 *
 * @param code    可区分的错误码，如 INVALID_ARGUMENT、NOT_FOUND、CONFLICT、CAPACITY_EXCEEDED
 * @param message 面向调用者的错误描述
 */
public record ErrorBody(String code, String message) {
}
