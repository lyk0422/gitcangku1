package com.example.starter.calibration.api.dto;

/**
 * 创建测量批次请求。目标值与允许偏差为最多 6 位小数的十进制字符串。
 *
 * @param requestId        调用方请求 ID（幂等键）
 * @param batchKey         业务批次键，全局唯一
 * @param instrumentId     仪器 ID
 * @param certificateId    绑定的校准证书 ID（证书版本）
 * @param targetValue      目标值，十进制字符串
 * @param allowedDeviation 允许偏差（含端点），非负十进制字符串
 */
public record CreateOutlierBatchRequest(
        String requestId,
        String batchKey,
        String instrumentId,
        Long certificateId,
        String targetValue,
        String allowedDeviation) {
}
