package com.example.starter.pass.api;

/**
 * 统一错误响应体。
 *
 * @param code    可区分拒绝原因码，见 {@code RejectReason}
 * @param message 面向调用方的说明（不含堆栈）
 */
public record ErrorBody(String code, String message) {
}
