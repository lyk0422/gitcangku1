package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 移交令实体，对应 transfer_order 表。按案件对签发，UTC 左闭右开有效期。
 *
 * @param id            主键
 * @param orderVersion  移交令版本，全局唯一
 * @param sourceCaseKey 来源案件键
 * @param targetCaseKey 目标案件键
 * @param status        令状态
 * @param validFrom     生效时刻（UTC，左闭）
 * @param validUntil    失效时刻（UTC，右开）
 * @param createdBy     签发操作人
 * @param createdAt     签发时间（UTC）
 * @param revokedAt     撤销时间（UTC）；null 表示未撤销
 */
public record TransferOrder(
        Long id,
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
