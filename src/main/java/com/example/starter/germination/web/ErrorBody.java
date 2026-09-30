package com.example.starter.germination.web;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status   HTTP 状态码
 * @param reason   机器可读拒绝原因
 * @param message  人类可读说明
 * @param at       错误发生时刻
 */
public record ErrorBody(int status, String reason, String message, Instant at) {
}
