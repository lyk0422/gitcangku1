package com.example.starter.bridge.api;

/**
 * 统一错误响应体。
 *
 * @param code 稳定错误码
 * @param message 错误说明
 */
public record ErrorResponse(String code, String message) {
}