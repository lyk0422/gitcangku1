package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量放行成功响应。
 *
 * @param batchId    放行批次 ID
 * @param releasedBy 放行人（X-Actor-Id）
 * @param releasedAt 放行时间（UTC）
 * @param released   已放行的测量键
 */
public record ReleaseResponse(String batchId, String releasedBy, Instant releasedAt, List<String> released) {
}
