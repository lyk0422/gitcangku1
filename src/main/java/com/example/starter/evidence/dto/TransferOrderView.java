package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 移交令视图。
 *
 * @param orderVersion   移交令版本
 * @param sourceCaseKey  令适用的来源案件键
 * @param targetCaseKey  令适用的目标案件键
 * @param validFrom      有效期起点（含），UTC
 * @param validTo        有效期终点（不含），UTC
 * @param createdBy      登记操作人
 * @param createdAt      登记时间（UTC）
 */
public record TransferOrderView(
        String orderVersion,
        String sourceCaseKey,
        String targetCaseKey,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        String createdBy,
        LocalDateTime createdAt) {
}
