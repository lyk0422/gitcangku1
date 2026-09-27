package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 移交令实体，对应 transfer_order 表。登记后不可修改；有效期按 UTC 左闭右开解释。
 *
 * @param id             主键
 * @param orderVersion   移交令版本，全局唯一
 * @param sourceCaseKey  令适用的来源案件键
 * @param targetCaseKey  令适用的目标案件键
 * @param validFrom      有效期起点（含），UTC
 * @param validTo        有效期终点（不含），UTC
 * @param createdBy      登记操作人
 * @param createdAt      登记时间（UTC）
 */
public record TransferOrder(
        Long id,
        String orderVersion,
        String sourceCaseKey,
        String targetCaseKey,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        String createdBy,
        LocalDateTime createdAt) {
}
