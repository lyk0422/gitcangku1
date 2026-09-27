package com.example.starter.evidence.dto;

import com.example.starter.evidence.CaseTransferStatus;

import java.time.LocalDateTime;

/**
 * 跨案移交头视图。
 *
 * @param transferId            移交业务键
 * @param sourceCaseKey         来源案件键
 * @param targetCaseKey         目标案件键
 * @param orderVersion          移交令版本
 * @param sourceCustodian       来源案件保管人
 * @param targetCustodian       目标案件保管人
 * @param status                移交状态
 * @param actorId               发起移交的操作人
 * @param evidenceCount         本批移交证物数量（实际值）
 * @param createdAt             移交完成时间（UTC）
 * @param revokedAt             撤销时间（UTC）；null 表示未撤销
 * @param revokeSourceConfirmer 撤销来源方确认人；null 表示未撤销
 * @param revokeTargetConfirmer 撤销目标方确认人；null 表示未撤销
 */
public record CaseTransferView(
        String transferId,
        String sourceCaseKey,
        String targetCaseKey,
        String orderVersion,
        String sourceCustodian,
        String targetCustodian,
        CaseTransferStatus status,
        String actorId,
        int evidenceCount,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String revokeSourceConfirmer,
        String revokeTargetConfirmer) {
}
