package com.example.starter.batch.dto;

/**
 * 准入门禁拒绝响应体（422）：携带当前滑动评分与门槛，便于调用方追溯。
 */
public record GateRejectedResponse(String code, String message, int currentScore, int threshold) {
}
