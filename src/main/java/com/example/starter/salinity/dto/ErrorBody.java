package com.example.starter.salinity.dto;

import java.time.Instant;

/**
 * 错误响应体。
 *
 * @param code    机器可读错误码
 * @param message 人类可读说明
 * @param path    请求路径
 * @param atUtc   错误发生时刻（UTC）
 */
public record ErrorBody(String code, String message, String path, Instant atUtc) {
}
