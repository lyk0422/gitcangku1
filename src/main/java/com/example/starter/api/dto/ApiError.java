package com.example.starter.api.dto;

/**
 * 统一错误响应体。
 */
public record ApiError(String error, String message) {
}
