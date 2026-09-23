package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 组合借出包实体，对应 loan_package 表。
 * 创建时全部证物同案件、同保管点；借出期间 custodianId 不变；
 * 最后一批归还事务内 status 自动 PARTIAL→CLOSED 并记录 closedAt，不能单独调用关闭。
 *
 * @param id          主键
 * @param packageKey  组合包业务键，全局唯一
 * @param caseKey     包内全部证物所属案件键，创建后不可变
 * @param custodianId 创建时统一保管点（当前保管人），借出期间不变
 * @param borrowerId  统一借用人
 * @param handlerId   提交组合借出的经办人（创建时的保管人）
 * @param purpose     借出用途
 * @param loanAt      实际借出时刻（UTC）
 * @param dueAt       统一 UTC 到期时刻
 * @param status      组合包状态
 * @param closedAt    自动关闭时刻（UTC）；null 表示尚未全部归还
 * @param createdAt   记录创建时间（Asia/Shanghai）
 * @param updatedAt   最近一次批次归还/撤销/关闭时间（Asia/Shanghai）
 */
public record LoanPackage(
        Long id,
        String packageKey,
        String caseKey,
        String custodianId,
        String borrowerId,
        String handlerId,
        String purpose,
        LocalDateTime loanAt,
        LocalDateTime dueAt,
        PackageStatus status,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
