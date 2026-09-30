package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 豁免审批记录视图。
 *
 * @param approver         审批人标识
 * @param exceptionVersion 该审批针对的申请版本号
 * @param approvedAt       审批时间，UTC 时间戳
 */
public record ExceptionApprovalView(
        String approver,
        int exceptionVersion,
        Instant approvedAt) {
}
