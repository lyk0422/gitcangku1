package com.example.starter.calibration.service;

/**
 * 拟合写操作的输出：HTTP 状态码 + 完整响应体（JSON 字符串）。
 * 首次执行与幂等重放使用同一结构，保证重放返回首次完整响应。
 *
 * @param status HTTP 状态码
 * @param body   响应体（JSON）
 */
public record FitOutcome(int status, String body) {
}
