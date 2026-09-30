package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次当前修订放行成功响应。
 *
 * @param batchKey     批次键
 * @param revisionNo   放行的测量修订版本号
 * @param releaseBatchId 放行批次 ID（UUID），与放行历史共享
 * @param releasedBy   放行人
 * @param releasedAt   放行时间（UTC）
 * @param released     实际放行测量键（按提交顺序）
 * @param activeCount  要求/实际放行数量
 */
public record BatchReleaseResponse(
        String batchKey,
        int revisionNo,
        String releaseBatchId,
        String releasedBy,
        Instant releasedAt,
        List<String> released,
        int activeCount) {
}
