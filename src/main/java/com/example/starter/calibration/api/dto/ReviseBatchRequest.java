package com.example.starter.calibration.api.dto;

/**
 * 测量修订请求。目标值与允许偏差为最多 6 位小数的十进制字符串。
 *
 * @param targetValue      新目标值，十进制字符串
 * @param allowedDeviation 新允许偏差（>=0），十进制字符串；合格区间左闭右开
 */
public record ReviseBatchRequest(
        String targetValue,
        String allowedDeviation) {
}
