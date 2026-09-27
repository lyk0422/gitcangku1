package com.example.starter.calibration.service;

/**
 * 幂等响应：HTTP 状态码 + 已序列化的 JSON 响应体。
 * 首次执行返回业务结果；同参重放原样返回首次结果。
 *
 * @param status HTTP 状态码
 * @param body   JSON 响应体
 */
public record IdempotentResponse(int status, String body) {
}
