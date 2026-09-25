package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 驳回历史响应。
 *
 * @param rejectedBy 驳回人
 * @param reason     驳回原因
 * @param rejectedAt 驳回时间（UTC）
 */
public record RejectRecordResponse(String rejectedBy, String reason, Instant rejectedAt) {
}
