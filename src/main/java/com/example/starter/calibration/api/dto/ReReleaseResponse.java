package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 重新放行成功响应。
 *
 * @param newBatchId 新生成的放行批次 ID
 * @param sourceBatchId 来源（被复核驳回）批次 ID
 * @param requestId  重新放行幂等键
 * @param releasedBy 放行人
 * @param releasedAt 放行时间（UTC）
 * @param items      新批次逐位置采用结果（按位置升序）
 */
public record ReReleaseResponse(
        String newBatchId,
        String sourceBatchId,
        String requestId,
        String releasedBy,
        Instant releasedAt,
        List<ReReleaseItemResult> items) {

    /**
     * 新批次单个位置的采用结果。
     *
     * @param position       位置（从 1 开始）
     * @param measurementKey 实际采用测量业务键
     * @param version        采用版本
     * @param revised        是否使用了修订
     */
    public record ReReleaseItemResult(int position, String measurementKey, int version, boolean revised) {
    }
}
