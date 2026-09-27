package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 双案保管链条目视图。
 *
 * @param transferKey 所属移交业务键
 * @param evidenceKey 证物业务键
 * @param caseKey     链条所属案件键
 * @param direction   方向：OUT 移出该案件 / IN 移入该案件
 * @param entryKind   条目类型：TRANSFER 移交 / REVOKE 撤销反向
 * @param custodianId 该环节保管人
 * @param createdAt   链条写入时间（UTC）
 */
public record CaseTransferChainEntryView(
        String transferKey,
        String evidenceKey,
        String caseKey,
        String direction,
        String entryKind,
        String custodianId,
        LocalDateTime createdAt) {
}
