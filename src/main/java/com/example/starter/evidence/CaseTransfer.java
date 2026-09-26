package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案移交批次，对应 case_transfer 表。记录只追加一次、状态仅可由 COMPLETED 变为 REVOKED；
 * 撤销不删除原记录，confirmers 与 revokedAt 在撤销时一次性写入。
 *
 * @param id                      主键
 * @param transferId              跨案移交业务键，全局唯一
 * @param sourceCaseKey           来源案件键
 * @param targetCaseKey           目标案件键
 * @param orderVersion            移交令版本
 * @param sourceCustodianId       来源案件保管人
 * @param targetCustodianId       目标案件保管人
 * @param status                  批次状态
 * @param evidenceCount           本批证物实际数量
 * @param createdAt               移交完成时间（UTC）
 * @param revokedAt               撤销时间（UTC）；null 表示未撤销
 * @param revokeSourceCustodianId 撤销时来源方确认保管人；null 表示未撤销
 * @param revokeTargetCustodianId 撤销时目标方确认保管人；null 表示未撤销
 */
public record CaseTransfer(
        Long id,
        String transferId,
        String sourceCaseKey,
        String targetCaseKey,
        String orderVersion,
        String sourceCustodianId,
        String targetCustodianId,
        CaseTransferStatus status,
        int evidenceCount,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String revokeSourceCustodianId,
        String revokeTargetCustodianId) {
}
