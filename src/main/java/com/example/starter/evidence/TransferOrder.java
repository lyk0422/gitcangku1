package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 移交令版本实体，对应 transfer_order 表。
 * 生效区间左闭右开 [validFrom, validTo)；validTo 为 null 表示长期有效。
 *
 * @param id          主键
 * @param orderNo     移交令编号
 * @param version     移交令版本（从 1 开始）
 * @param fromCaseKey 移交令载明的来源案件键
 * @param toCaseKey   移交令载明的目标案件键
 * @param status      版本状态
 * @param validFrom   UTC 生效起始时刻（含）
 * @param validTo     UTC 生效结束时刻（不含）；null 表示长期有效
 * @param createdAt   版本创建时间（UTC）
 */
public record TransferOrder(
        Long id,
        String orderNo,
        int version,
        String fromCaseKey,
        String toCaseKey,
        OrderStatus status,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        LocalDateTime createdAt) {
}
