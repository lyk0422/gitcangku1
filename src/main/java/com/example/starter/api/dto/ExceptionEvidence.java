package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁文件中固化的豁免依据快照：历史锁文件保留锁定当时的豁免内容、状态与审批人，
 * 豁免事后被撤销或到期不改变该快照。
 *
 * @param exceptionId       豁免申请 ID
 * @param dependencyName    豁免依赖名称
 * @param dependencyVersion 豁免依赖精确版本
 * @param expiresAt         豁免到期 UTC 时刻
 * @param reason            豁免原因
 * @param status            锁定时豁免状态（生效锁定固定为 APPROVED）
 * @param approvers         两名审批人
 * @param lockedAt          锁定时刻，UTC
 */
public record ExceptionEvidence(
        long exceptionId,
        String dependencyName,
        int dependencyVersion,
        Instant expiresAt,
        String reason,
        String status,
        List<String> approvers,
        Instant lockedAt) {
}
