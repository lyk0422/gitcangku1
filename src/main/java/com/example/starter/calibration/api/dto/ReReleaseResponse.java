package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 重新放行成功响应。
 *
 * @param batchId       新放行批次 ID
 * @param sourceBatchId 来源批次 ID
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 * @param items         新批次全部位置使用的测量（按键升序）
 */
public record ReReleaseResponse(String batchId, String sourceBatchId, String releasedBy,
                                Instant releasedAt, List<ReReleasedItem> items) {

    /**
     * 新批次中的一个位置。
     *
     * @param measurementKey 测量键
     * @param version        使用的测量版本号
     */
    public record ReReleasedItem(String measurementKey, int version) {
    }
}
