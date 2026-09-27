package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 移交令视图。
 *
 * @param orderVersion  移交令版本
 * @param sourceCaseKey 来源案件键
 * @param targetCaseKey 目标案件键
 * @param status        令状态：VALID / REVOKED
 * @param createdBy     登记操作人
 * @param createdAt     登记时间（UTC）
 * @param revokedAt     撤销时间（UTC）；null 表示仍有效
 */
public record TransferOrderView(
        String orderVersion,
        String sourceCaseKey,
        String targetCaseKey,
        String status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {
}
