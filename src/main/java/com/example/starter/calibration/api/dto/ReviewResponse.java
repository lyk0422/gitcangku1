package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 复核成功响应。
 *
 * @param batchId    被复核的批次 ID
 * @param reviewKey  复核幂等键
 * @param reviewer   复核人
 * @param status     复核后批次状态（REVIEW_REQUIRED）
 * @param reviewedAt 复核时间（UTC）
 * @param rejected   被驳回位置列表（升序）
 */
public record ReviewResponse(
        String batchId,
        String reviewKey,
        String reviewer,
        String status,
        Instant reviewedAt,
        List<Integer> rejected) {
}
