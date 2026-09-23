package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 复核驳回成功响应。
 *
 * @param reviewKey  复核幂等键
 * @param batchId    被复核的放行批次 ID
 * @param reviewer   复核人
 * @param reviewedAt 复核时间（UTC）
 * @param rejected   被驳回的测量项
 */
public record ReviewResponse(String reviewKey, String batchId, String reviewer, Instant reviewedAt,
                             List<RejectedItem> rejected) {

    /**
     * 被驳回的测量项。
     *
     * @param measurementKey 测量键
     * @param version        驳回时冻结的测量版本号
     * @param reason         驳回原因
     */
    public record RejectedItem(String measurementKey, int version, String reason) {
    }
}
