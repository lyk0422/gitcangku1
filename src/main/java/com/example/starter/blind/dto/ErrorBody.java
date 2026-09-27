package com.example.starter.blind.dto;

/**
 * 统一错误响应体。
 *
 * @param status  HTTP 状态码数值
 * @param error   HTTP 状态原因短语
 * @param code    机器可区分的业务错误原因标识（如 CODE_NOT_FOUND）；无细分时取 HTTP 原因短语
 * @param message 安全提示信息（不含盲底）
 */
public record ErrorBody(int status, String error, String code, String message) {
}
