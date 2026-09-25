package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 驳回请求。
 *
 * @param reason 驳回原因
 */
public record RejectRequest(String reason) {
}
