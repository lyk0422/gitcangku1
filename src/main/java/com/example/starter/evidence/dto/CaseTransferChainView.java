package com.example.starter.evidence.dto;

import com.example.starter.evidence.ChainEntryType;

import java.time.LocalDateTime;

/**
 * 双案保管链条目视图。
 *
 * @param transferId   所属移交业务键
 * @param evidenceKey  证物业务键
 * @param caseKey      本链条目所属案件键
 * @param entryType    链条目类型
 * @param custodianId  该案件侧保管人
 * @param orderVersion 移交令版本
 * @param createdAt    链条目写入时间（UTC）
 */
public record CaseTransferChainView(
        String transferId,
        String evidenceKey,
        String caseKey,
        ChainEntryType entryType,
        String custodianId,
        String orderVersion,
        LocalDateTime createdAt) {
}
