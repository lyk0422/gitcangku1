package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案移交主单实体，对应 case_transfer 表。
 *
 * @param id            transferId（主键）
 * @param fromCaseKey   来源案件键
 * @param toCaseKey     目标案件键
 * @param orderKey      移交令编号
 * @param orderVersion  固化的令版本
 * @param fromCustodian 来源案件保管人
 * @param toCustodian   目标案件保管人
 * @param status        移交状态
 * @param initiatedBy   发起操作人
 * @param evidenceCount 实际移交证物数量
 * @param createdAt     移交完成时刻（UTC，微秒精度）
 * @param decidedAt     撤销时刻（UTC）；null 表示未撤销
 * @param revokedByFrom 撤销确认的来源案件保管人；null 表示未撤销
 * @param revokedByTo   撤销确认的目标案件保管人；null 表示未撤销
 */
public record CaseTransfer(
        Long id,
        String fromCaseKey,
        String toCaseKey,
        String orderKey,
        String orderVersion,
        String fromCustodian,
        String toCustodian,
        CaseTransferStatus status,
        String initiatedBy,
        int evidenceCount,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        String revokedByFrom,
        String revokedByTo) {
}
