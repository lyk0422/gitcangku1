package com.example.starter.evidence.dto;

import com.example.starter.evidence.TransferStatus;
import com.example.starter.evidence.TransferType;

import java.time.LocalDateTime;

/**
 * 交接记录视图。
 *
 * @param evidenceKey   关联证物业务键
 * @param fromCustodian 发起交接时的保管人
 * @param toCustodian   指定接收人（受监管交接中同时为运送人）
 * @param status        交接状态
 * @param transferType  交接类型：REGULAR 普通 / REGULATED 受监管
 * @param frozen        是否已冻结：true 表示在途签到出现异常，禁止接收与继续签到
 * @param initiatedBy   发起操作人
 * @param createdAt     发起时间（Asia/Shanghai）
 * @param decidedAt     接受或取消时间；null 表示仍待接收
 */
public record TransferView(
        String evidenceKey,
        String fromCustodian,
        String toCustodian,
        TransferStatus status,
        TransferType transferType,
        boolean frozen,
        String initiatedBy,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}