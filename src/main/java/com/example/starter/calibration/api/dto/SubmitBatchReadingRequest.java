package com.example.starter.calibration.api.dto;

/**
 * 向批次当前修订提交读数请求。仪器取自批次，目标值/允许偏差取自当前修订版本。
 *
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param measuredAt     测量时刻，ISO-8601（按 UTC 归一）
 * @param reading        原始读数，十进制字符串
 * @param submittedBy    读数提交人（不得复核本读数离群记录）
 */
public record SubmitBatchReadingRequest(
        String measurementKey,
        String measuredAt,
        String reading,
        String submittedBy) {
}
