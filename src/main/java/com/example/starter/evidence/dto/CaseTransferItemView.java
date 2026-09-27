package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 双案封存快照视图。字段在移交时固化，后续操作不改写。
 *
 * @param transferKey       所属移交业务键
 * @param evidenceKey       证物业务键
 * @param sourceCaseKey     移交时来源案件（固化）
 * @param targetCaseKey     移交时目标案件（固化）
 * @param sourceCustodianId 移交时来源保管人（固化）
 * @param targetCustodianId 移交时目标保管人（固化）
 * @param sealNo            移交时封条编号（固化）
 * @param sealVersion       移交时封签版本：历史核验次数 + 1（固化）
 * @param handoverCount     移交时证物既有交接记录数（固化）
 * @param loanCount         移交时证物既有借出记录数（固化）
 * @param orderVersion      移交令版本（固化）
 * @param createdAt         快照时间（UTC）
 */
public record CaseTransferItemView(
        String transferKey,
        String evidenceKey,
        String sourceCaseKey,
        String targetCaseKey,
        String sourceCustodianId,
        String targetCustodianId,
        String sealNo,
        int sealVersion,
        int handoverCount,
        int loanCount,
        String orderVersion,
        LocalDateTime createdAt) {
}
