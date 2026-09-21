package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 交接记录实体，对应 transfer_record 表。记录只追加，决定（接受/取消）后状态不再变化。
 *
 * @param id            主键
 * @param evidenceKey   关联证物业务键
 * @param fromCustodian 发起交接时的保管人
 * @param toCustodian   指定接收人
 * @param status        交接状态
 * @param initiatedBy   发起操作人
 * @param createdAt     发起时间（Asia/Shanghai）
 * @param decidedAt     接受或取消时间；null 表示仍待接收
 */
public record TransferRecord(
        Long id,
        String evidenceKey,
        String fromCustodian,
        String toCustodian,
        TransferStatus status,
        String initiatedBy,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
