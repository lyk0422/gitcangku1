package com.example.starter.calibration.api.dto;

/**
 * 创建测量批次请求。目标值与允许偏差为最多 6 位小数的十进制字符串；允许区间左闭右开。
 *
 * @param batchKey     业务批次键，全局唯一
 * @param instrumentId 仪器 ID
 * @param targetValue  目标值；允许区间 [targetValue-tolerance, targetValue+tolerance]
 * @param tolerance    允许偏差（非负）
 * @param createdBy    批次创建人
 */
public record CreateBatchRequest(
        String batchKey,
        String instrumentId,
        String targetValue,
        String tolerance,
        String createdBy) {
}
