package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量放行成功响应。snapshots 为本次放行固化的完整结论快照（与 released 键顺序对应），
 * 重放同一 requestId 时原样返回首次快照。
 *
 * @param batchId    放行批次 ID
 * @param releasedBy 放行人（X-Actor-Id）
 * @param releasedAt 放行时间（UTC）
 * @param released   已放行的测量键（按请求顺序）
 * @param snapshots  各测量放行时刻固化的结论快照
 */
public record ReleaseResponse(String batchId, String releasedBy, Instant releasedAt,
                              List<String> released, List<ReleaseSnapshotResponse> snapshots) {
}
