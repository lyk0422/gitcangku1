package com.example.starter.calibration.api.dto;

/**
 * 创建测量批次请求。目标值与允许偏差为最多 6 位小数的十进制字符串。
 *
 * @param batchKey         业务批次键，全局唯一（幂等键）
 * @param instrumentId     仪器 ID
 * @param targetValue      目标值，十进制字符串
 * @param allowedDeviation 允许偏差（>=0），十进制字符串；合格区间左闭右开
 */
public record CreateBatchRequest(
        String batchKey,
        String instrumentId,
        String targetValue,
        String allowedDeviation) {
}
