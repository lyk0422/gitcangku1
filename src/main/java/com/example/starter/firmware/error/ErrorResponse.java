package com.example.starter.firmware.error;

/**
 * 统一错误响应体。
 *
 * @param code    稳定错误码
 * @param message 错误描述
 */
public record ErrorResponse(String code, String message) {
}
