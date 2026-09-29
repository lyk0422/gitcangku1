package com.example.starter.calibration.api.dto;

/**
 * 发布仪器不确定度阈值请求。阈值版本化、只增不改，每次发布产生新版本。
 *
 * @param instrumentId   仪器 ID
 * @param maxUncertainty 合成不确定度上限（含端点），非负、最多 6 位小数的十进制字符串
 */
public record PublishThresholdRequest(String instrumentId, String maxUncertainty) {
}
