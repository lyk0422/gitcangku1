package com.example.starter.evidence.dto;

import com.example.starter.evidence.CaseTransferStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨案移交单视图。
 *
 * @param transferId    移交主键，同一批次双案快照共用
 * @param fromCaseKey   来源案件键
 * @param toCaseKey     目标案件键
 * @param orderKey      移交令编号
 * @param orderVersion  固化的令版本
 * @param fromCustodian 来源案件保管人
 * @param toCustodian   目标案件保管人
 * @param status        移交状态：COMPLETED / REVOKED
 * @param initiatedBy   发起操作人
 * @param evidenceKeys  按规范化排序固化的证物键
 * @param requestedCount 要求移交数量（请求去重后数量）
 * @param evidenceCount 实际移交数量（与要求值一致才提交）
 * @param createdAt     移交完成时刻（UTC，微秒精度）
 * @param decidedAt     撤销时刻（UTC）；null 表示未撤销
 * @param revokedByFrom 撤销确认的来源案件保管人；null 表示未撤销
 * @param revokedByTo   撤销确认的目标案件保管人；null 表示未撤销
 */
public record CaseTransferView(
        long transferId,
        String fromCaseKey,
        String toCaseKey,
        String orderKey,
        String orderVersion,
        String fromCustodian,
        String toCustodian,
        CaseTransferStatus status,
        String initiatedBy,
        List<String> evidenceKeys,
        int requestedCount,
        int evidenceCount,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        String revokedByFrom,
        String revokedByTo) {
}
