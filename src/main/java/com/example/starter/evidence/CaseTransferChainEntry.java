package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双案保管链条目实体，对应 case_transfer_chain 表。记录只追加、不可变，不提供任何更新语句。
 *
 * @param id           主键
 * @param transferId   所属移交业务键（正反向链共用同一 transferId）
 * @param evidenceKey  证物业务键
 * @param caseKey      本链条目所属案件键
 * @param entryType    链条目类型
 * @param custodianId  该案件侧保管人
 * @param orderVersion 移交令版本
 * @param createdAt    链条目写入时间（UTC）
 */
public record CaseTransferChainEntry(
        Long id,
        String transferId,
        String evidenceKey,
        String caseKey,
        ChainEntryType entryType,
        String custodianId,
        String orderVersion,
        LocalDateTime createdAt) {
}
