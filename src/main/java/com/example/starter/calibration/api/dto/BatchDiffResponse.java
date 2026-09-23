package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次差异查询响应（只读）。展示批次状态、复核信息及每个位置的驳回差异。
 *
 * @param batchId          批次 ID
 * @param status           批次状态：RELEASED / REVIEW_REQUIRED / SUPERSEDED
 * @param releasedBy       放行人
 * @param releasedAt       放行时间（UTC）
 * @param sourceBatchId    重新放行来源批次 ID；首次批量放行为 null
 * @param successorBatchId 取代本批次的重新放行批次 ID；未被取代为 null
 * @param reviewKey        复核幂等键；未复核为 null
 * @param reviewer         复核人；未复核为 null
 * @param reviewedAt       复核时间（UTC）；未复核为 null
 * @param items            批次全部位置的差异项
 */
public record BatchDiffResponse(String batchId, String status, String releasedBy, Instant releasedAt,
                                String sourceBatchId, String successorBatchId,
                                String reviewKey, String reviewer, Instant reviewedAt,
                                List<BatchDiffItem> items) {

    /**
     * 批次中一个位置的差异项。
     *
     * @param measurementKey 测量键
     * @param version        冻结的测量版本号
     * @param rejected       该位置是否被驳回
     * @param reason         驳回原因；未驳回为 null
     */
    public record BatchDiffItem(String measurementKey, int version, boolean rejected, String reason) {
    }
}
