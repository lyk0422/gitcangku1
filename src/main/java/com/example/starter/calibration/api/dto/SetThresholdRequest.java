package com.example.starter.calibration.api.dto;

/**
 * 登记仪器不确定度阈值请求。每次登记生成递增版本，历史版本保留。
 *
 * @param requestId 幂等请求 ID（必填；同参重放返回首次快照，异参 409，失败不占键）
 * @param threshold 阈值取值（非负十进制字符串，最多 6 位小数；缺失或非法返回 422）
 */
public record SetThresholdRequest(String requestId, String threshold) {
}
