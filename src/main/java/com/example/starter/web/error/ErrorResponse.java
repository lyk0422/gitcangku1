package com.example.starter.web.error;

/**
 * 统一错误响应体。
 *
 * @param code    拒绝原因代码，取值见 {@link ErrorCode}
 * @param message 拒绝原因说明
 */
public record ErrorResponse(String code, String message) {
}
