package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双案保管链条目实体，对应 case_transfer_chain 表。
 * 每次移交对每证物写出来源案件移出链（OUT）与目标案件移入链（IN）；
 * 撤销追加反向链（entry_kind=REVOKE），不删除原移交链。记录只追加、不可变。
 *
 * @param id          主键
 * @param transferKey 所属移交业务键
 * @param evidenceKey 证物业务键
 * @param caseKey     链条所属案件键
 * @param direction   方向：OUT 移出该案件 / IN 移入该案件
 * @param entryKind   条目类型：TRANSFER 移交 / REVOKE 撤销反向
 * @param custodianId 该环节保管人
 * @param createdAt   链条写入时间（UTC）
 */
public record CaseTransferChainEntry(
        Long id,
        String transferKey,
        String evidenceKey,
        String caseKey,
        String direction,
        String entryKind,
        String custodianId,
        LocalDateTime createdAt) {
}
