package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行批次头。仅成功放行的批次存在记录，用于放行诊断查询；失败的批次不留任何状态。
 *
 * @param batchId    放行批次 ID（UUID）
 * @param releasedBy 放行人（X-Actor-Id）
 * @param releasedAt 放行时间（UTC）
 * @param itemCount  批次内测量条数
 */
public record ReleaseBatch(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        int itemCount) {
}
