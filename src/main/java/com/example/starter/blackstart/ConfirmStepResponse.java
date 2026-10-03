package com.example.starter.blackstart;

import java.time.Instant;

/**
 * 步骤确认响应。outcome 为 SUCCESS 或 REJECTED；被拒绝时 rejectReason 说明原因，步骤号不推进。
 */
public record ConfirmStepResponse(
        long planId,
        int stepNo,
        String requestId,
        String outcome,
        String rejectReason,
        String planStatus,
        int currentStep,
        Instant recordedAt) {
}