package com.example.starter.evidence.dto;

import com.example.starter.evidence.CaseTransferStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨案移交明细视图：主单字段 + 规范化排序后的证物明细。
 *
 * @param transferId          跨案移交业务 ID
 * @param orderNo             移交令编号
 * @param orderVersion        移交令版本
 * @param fromCaseKey         来源案件键
 * @param toCaseKey           目标案件键
 * @param fromCustodian       来源案件保管人
 * @param toCustodian         目标案件保管人
 * @param status              移交状态：TRANSFERRED / REVOKED
 * @param requestedItemCount  本次请求证物数量（实际值）
 * @param createdAt           移交完成时刻（UTC）
 * @param revokedAt           撤销时刻（UTC）；null 表示未撤销
 * @param revokeFromCustodian 撤销时确认的来源案件保管人；null 表示未撤销
 * @param revokeToCustodian   撤销时确认的目标案件保管人；null 表示未撤销
 * @param items               规范化排序后的证物明细
 */
public record CaseTransferView(
        String transferId,
        String orderNo,
        int orderVersion,
        String fromCaseKey,
        String toCaseKey,
        String fromCustodian,
        String toCustodian,
        CaseTransferStatus status,
        int requestedItemCount,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String revokeFromCustodian,
        String revokeToCustodian,
        List<CaseTransferItemView> items) {
}
