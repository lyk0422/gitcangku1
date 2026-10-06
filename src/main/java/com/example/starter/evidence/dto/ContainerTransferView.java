package com.example.starter.evidence.dto;

import com.example.starter.evidence.TransferStatus;

import java.time.LocalDateTime;

/**
 * 根箱交接记录视图。
 *
 * @param containerKey  根箱业务键
 * @param fromCustodian 发起交接时的保管人
 * @param toCustodian   指定接收人
 * @param status        交接状态
 * @param initiatedBy   发起操作人
 * @param createdAt     交接发起时间（Asia/Shanghai）
 * @param decidedAt     接受或取消时间；NULL 表示仍待接收
 */
public record ContainerTransferView(
        String containerKey,
        String fromCustodian,
        String toCustodian,
        TransferStatus status,
        String initiatedBy,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
