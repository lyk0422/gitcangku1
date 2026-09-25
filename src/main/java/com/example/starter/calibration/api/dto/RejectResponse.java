package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量驳回成功响应。
 *
 * @param batchId    驳回批次 ID
 * @param rejectedBy  驳回人（X-Actor-Id）
 * @param rejectedAt  驳回时间（UTC）
 * @param reason     驳回原因
 * @param rejected   被驳回的测量键（稳定字典序）
 */
public record RejectResponse(
        String batchId,
        String rejectedBy,
        Instant rejectedAt,
        String reason,
        List<String> rejected) {
}
