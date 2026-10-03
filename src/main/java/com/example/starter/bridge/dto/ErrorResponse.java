package com.example.starter.bridge.dto;

/**
 * 错误响应；code 为机器可读原因代码，message 为说明。
 */
public record ErrorResponse(
        /** 原因代码，如 OVERWEIGHT、WINDOW_OVERLAP。 */
        String code,
        /** 原因说明。 */
        String message) {
}