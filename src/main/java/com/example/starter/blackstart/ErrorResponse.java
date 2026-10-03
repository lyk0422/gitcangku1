package com.example.starter.blackstart;

/**
 * 错误响应体。
 *
 * @param error 稳定错误码
 * @param message 错误描述
 */
public record ErrorResponse(String error, String message) {
}