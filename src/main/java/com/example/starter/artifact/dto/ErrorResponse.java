package com.example.starter.artifact.dto;

/**
 * 统一错误响应体。
 *
 * @param error   错误类别（HTTP 状态短语）
 * @param message 面向调用方的错误描述
 */
public record ErrorResponse(String error, String message) {
}
