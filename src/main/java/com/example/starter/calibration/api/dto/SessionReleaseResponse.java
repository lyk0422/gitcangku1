package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 会话批量放行成功响应。
 *
 * @param batchId             放行批次 ID（UUID），同一批原子放行共享
 * @param releasedBy          放行人（X-Actor-Id）
 * @param releasedAt          放行时间（UTC）
 * @param releasedSessions    已放行的会话键
 * @param releasedMeasurements 已放行的测量键（全部会话成员）
 */
public record SessionReleaseResponse(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        List<String> releasedSessions,
        List<String> releasedMeasurements) {
}
