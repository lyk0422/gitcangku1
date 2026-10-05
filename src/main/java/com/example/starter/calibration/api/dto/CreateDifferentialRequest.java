package com.example.starter.calibration.api.dto;

/**
 * 创建差分记录请求。
 *
 * @param referenceKey 参考测量键
 * @param dutKey       待测测量键
 * @param lowerLimit   差值合格下限（含端点，十进制字符串）
 * @param upperLimit   差值合格上限（含端点，十进制字符串）
 */
public record CreateDifferentialRequest(
        String referenceKey,
        String dutKey,
        String lowerLimit,
        String upperLimit) {
}
