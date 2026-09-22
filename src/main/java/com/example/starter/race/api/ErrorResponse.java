package com.example.starter.race.api;

/**
 * 错误响应体。
 *
 * @param error   错误类型代码
 * @param message 可读错误信息
 */
public record ErrorResponse(String error, String message) {
}
