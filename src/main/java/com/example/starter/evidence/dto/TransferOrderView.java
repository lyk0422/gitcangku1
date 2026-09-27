package com.example.starter.evidence.dto;

import com.example.starter.evidence.TransferOrderStatus;

import java.time.LocalDateTime;

/**
 * 移交令视图。
 *
 * @param orderVersion  移交令版本
 * @param sourceCaseKey 来源案件键
 * @param targetCaseKey 目标案件键
 * @param status        令状态
 * @param validFrom     生效时刻（UTC，左闭）
 * @param validUntil    失效时刻（UTC，右开）
 * @param createdBy     签发操作人
 * @param createdAt     签发时间（UTC）
 * @param revokedAt     撤销时间（UTC）；null 表示未撤销
 */
public record TransferOrderView(
        String orderVersion,
        String sourceCaseKey,
        String targetCaseKey,
        TransferOrderStatus status,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {
}
