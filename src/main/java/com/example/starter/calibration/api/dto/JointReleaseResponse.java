package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 联合批次放行成功响应。released 按测量键字典序输出，保证同键重放时响应快照一致。
 *
 * @param jointBatchKey 联合批次键
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 * @param released      已放行的测量键（字典序）
 */
public record JointReleaseResponse(String jointBatchKey, String releasedBy, Instant releasedAt,
                                   List<String> released) {
}
