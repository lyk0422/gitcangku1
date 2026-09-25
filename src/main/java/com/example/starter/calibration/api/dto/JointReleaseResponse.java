package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 联合批次放行成功响应（同键同参重放时返回首次响应快照）。
 *
 * @param jointBatchKey 联合批次业务键
 * @param requestId     请求幂等键
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 * @param released      本次放行的测量键（按请求顺序返回）
 */
public record JointReleaseResponse(
        String jointBatchKey,
        String requestId,
        String releasedBy,
        Instant releasedAt,
        List<String> released) {
}
