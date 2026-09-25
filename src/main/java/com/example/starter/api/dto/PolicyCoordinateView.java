package com.example.starter.api.dto;

/**
 * 策略坐标要求视图；requiredDigest 为空串表示不校验构建摘要。
 */
public record PolicyCoordinateView(String name, int requiredLevel, String requiredDigest) {
}
