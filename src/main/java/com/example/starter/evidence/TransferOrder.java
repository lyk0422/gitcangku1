package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 移交令版本实体，对应 transfer_order 表。
 * 版本按 [effectiveFrom, effectiveTo) UTC 左闭右开区间生效；effectiveTo 为 null 表示长期有效。
 *
 * @param id            主键
 * @param orderKey      移交令编号
 * @param orderVersion  令版本
 * @param fromCaseKey   令中核准的来源案件键
 * @param toCaseKey     令中核准的目标案件键
 * @param effectiveFrom 版本生效起始时刻（UTC，含）
 * @param effectiveTo   版本失效时刻（UTC，不含）；null 表示长期有效
 * @param createdAt     版本登记时间（Asia/Shanghai）
 */
public record TransferOrder(
        Long id,
        String orderKey,
        String orderVersion,
        String fromCaseKey,
        String toCaseKey,
        LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,
        LocalDateTime createdAt) {
}
