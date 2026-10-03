package com.example.starter.web.dto;

/**
 * 统一错误响应。
 */
public record ErrorResponse(String code, String message) {
}