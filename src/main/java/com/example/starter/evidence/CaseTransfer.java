package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案移交主表实体，对应 case_transfer 表。记录只追加，撤销仅更新状态与撤销确认字段。
 *
 * @param id                    主键
 * @param transferKey           移交业务键（transferId），服务端生成，全局唯一
 * @param sourceCaseKey         来源案件键
 * @param targetCaseKey         目标案件键
 * @param orderVersion          移交令版本（快照固化）
 * @param sourceCustodianId     来源案件保管人（发起操作人）
 * @param targetCustodianId     目标案件保管人
 * @param evidenceCount         本批证物数量（实际值）
 * @param status                移交状态
 * @param createdAt             移交完成时间（UTC）
 * @param revokedAt             撤销时间（UTC）；null 表示未撤销
 * @param revokeSourceConfirmer 撤销来源方确认保管人；null 表示未撤销
 * @param revokeTargetConfirmer 撤销目标方确认保管人；null 表示未撤销
 */
public record CaseTransfer(
        Long id,
        String transferKey,
        String sourceCaseKey,
        String targetCaseKey,
        String orderVersion,
        String sourceCustodianId,
        String targetCustodianId,
        int evidenceCount,
        CaseTransferStatus status,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String revokeSourceConfirmer,
        String revokeTargetConfirmer) {
}
