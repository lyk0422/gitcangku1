package com.example.starter.maintenance.api.dto;

/**
 * 工单取消限制诊断。
 *
 * @param cancellable  未开始（CREATED）工单为 true，其余状态为 false
 * @param status       工单当前状态
 * @param reason       不可取消原因（cancellable=true 时为 null）
 */
public record CancelEligibilityResponse(
        String workOrderId,
        String status,
        boolean cancellable,
        String reason) {
}
