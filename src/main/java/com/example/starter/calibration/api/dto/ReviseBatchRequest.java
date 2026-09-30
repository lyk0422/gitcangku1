package com.example.starter.calibration.api.dto;

/**
 * 创建后续测量修订请求。仅当前修订已放行时允许；必须说明新增离群事实等原因。
 *
 * @param targetValue 新目标值
 * @param tolerance   新允许偏差（非负）
 * @param reason      修订原因（必填）
 * @param createdBy   修订创建人
 */
public record ReviseBatchRequest(
        String targetValue,
        String tolerance,
        String reason,
        String createdBy) {
}
