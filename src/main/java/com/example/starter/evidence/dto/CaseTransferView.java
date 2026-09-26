package com.example.starter.evidence.dto;

import com.example.starter.evidence.CaseTransferStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨案移交批次视图。所有时间为 UTC；null 语义稳定：
 * revokedAt/revokeSourceCustodianId/revokeTargetCustodianId 为 null 表示未撤销。
 *
 * @param transferId              跨案移交业务键
 * @param sourceCaseKey           来源案件键
 * @param targetCaseKey           目标案件键
 * @param orderVersion            移交令版本
 * @param sourceCustodianId       来源案件保管人
 * @param targetCustodianId       目标案件保管人
 * @param status                  批次状态
 * @param evidenceCount           本批证物实际数量
 * @param items                   双案封存快照明细（按证物键字典序）
 * @param createdAt               移交完成时间（UTC）
 * @param revokedAt               撤销时间（UTC）；null 表示未撤销
 * @param revokeSourceCustodianId 撤销时来源方确认保管人；null 表示未撤销
 * @param revokeTargetCustodianId 撤销时目标方确认保管人；null 表示未撤销
 */
public record CaseTransferView(
        String transferId,
        String sourceCaseKey,
        String targetCaseKey,
        String orderVersion,
        String sourceCustodianId,
        String targetCustodianId,
        CaseTransferStatus status,
        int evidenceCount,
        List<CaseTransferItemView> items,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String revokeSourceCustodianId,
        String revokeTargetCustodianId) {
}
