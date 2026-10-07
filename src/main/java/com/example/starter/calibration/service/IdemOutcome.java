package com.example.starter.calibration.service;

/**
 * 幂等执行结果：HTTP 状态与完整响应体（JSON 字符串）。
 * 重放时按原样返回首次响应。
 *
 * @param status HTTP 状态码
 * @param body   完整响应体（JSON）
 */
public record IdemOutcome(int status, String body) {
}
