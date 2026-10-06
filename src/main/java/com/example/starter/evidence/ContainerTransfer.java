package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 整箱交接记录，对应 container_transfer 表。只追加；仅根箱可发起。
 *
 * @param id            主键
 * @param containerKey  根箱业务键
 * @param fromCustodian 发起交接时的保管人
 * @param toCustodian   指定接收人，必须与发起人不同
 * @param status        交接状态（PENDING / ACCEPTED / CANCELLED）
 * @param initiatedBy   发起操作人（与 fromCustodian 相同）
 * @param createdAt     发起时间（Asia/Shanghai）
 * @param decidedAt     接受或取消时间；NULL 表示仍待接收
 */
public record ContainerTransfer(
        Long id,
        String containerKey,
        String fromCustodian,
        String toCustodian,
        TransferStatus status,
        String initiatedBy,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
