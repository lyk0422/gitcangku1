package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 会话批量放行成功响应。
 *
 * @param batchId              放行批次 ID
 * @param releasedBy           放行人（X-Actor-Id）
 * @param releasedAt           放行时间（UTC）
 * @param releasedSessions     已放行的会话 ID（按请求顺序）
 * @param releasedMeasurements 已放行的成员测量键（按会话内顺序）
 */
public record SessionReleaseResponse(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        List<Long> releasedSessions,
        List<String> releasedMeasurements) {
}
