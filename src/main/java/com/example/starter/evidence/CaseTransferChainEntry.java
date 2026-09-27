package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案移交保管链条目（只追加，不可变）。
 *
 * @param id             主键
 * @param transferId     跨案移交批次标识
 * @param evidenceKey    关联证物业务键
 * @param direction      链方向：OUT/IN/REVOKE_OUT/REVOKE_IN
 * @param caseKey        本条目所属案件键
 * @param counterCaseKey 对方案件键
 * @param custodianId    本条目确认保管人
 * @param orderVersion   移交令版本（固化）
 * @param createdAt      条目写入时间（UTC）
 */
public record CaseTransferChainEntry(
        long id,
        String transferId,
        String evidenceKey,
        ChainDirection direction,
        String caseKey,
        String counterCaseKey,
        String custodianId,
        String orderVersion,
        LocalDateTime createdAt) {
}
