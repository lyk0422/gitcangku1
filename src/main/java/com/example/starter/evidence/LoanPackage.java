package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 组合借出包实体，对应 loan_package 表。
 * 建包后 caseKey/custodianId/borrowerId/purpose/dueAt 不可修改；
 * 状态仅由分批归还驱动：PARTIAL →（最后一件归还时同事务）CLOSED，不可单独关闭。
 *
 * @param id          主键
 * @param packageKey  组合包业务键，全局唯一
 * @param caseKey     包内全部证物所属案件键
 * @param custodianId 建包时全部证物一致的保管点（当前保管人）
 * @param borrowerId  统一借用人
 * @param purpose     统一借出用途
 * @param dueAt       统一 UTC 应还时刻
 * @param loanAt      实际借出时刻（UTC）
 * @param status        组合包状态
 * @param closedAt      自动关闭时刻（UTC）；null 表示仍为 PARTIAL
 * @param closeSnapshot 关闭快照 JSON（全部借出明细与归还批次）；null 表示未关闭
 * @param createdAt     创建时间（Asia/Shanghai）
 * @param updatedAt     最近一次归还批次或关闭时间（Asia/Shanghai）
 */
public record LoanPackage(
        Long id,
        String packageKey,
        String caseKey,
        String custodianId,
        String borrowerId,
        String purpose,
        LocalDateTime dueAt,
        LocalDateTime loanAt,
        PackageStatus status,
        LocalDateTime closedAt,
        String closeSnapshot,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
