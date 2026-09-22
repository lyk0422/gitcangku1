package com.example.starter.blind.dto;

/**
 * 统一错误响应体。
 *
 * @param error   错误类型标识
 * @param message 安全提示信息（不含盲底）
 */
public record ErrorBody(String error, String message) {
}
