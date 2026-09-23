package com.example.starter.evidence.dto;

import com.example.starter.evidence.PackageStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合借出包视图。只读查询，明细与批次均按稳定排序返回。
 *
 * @param packageKey  组合包业务键
 * @param caseKey     所属案件键
 * @param custodianId 建包时统一保管点
 * @param borrowerId  统一借用人
 * @param purpose     统一借出用途
 * @param dueAt       统一 UTC 应还时刻
 * @param loanAt      实际借出时刻（UTC）
 * @param status      组合包状态
 * @param closedAt    自动关闭时刻（UTC）；null 表示仍为 PARTIAL
 * @param items       全部逐件借出明细（按包内稳定排序）
 * @param batches     全部归还批次（按提交顺序）
 */
public record LoanPackageView(
        String packageKey,
        String caseKey,
        String custodianId,
        String borrowerId,
        String purpose,
        LocalDateTime dueAt,
        LocalDateTime loanAt,
        PackageStatus status,
        LocalDateTime closedAt,
        List<PackageItemView> items,
        List<PackageBatchView> batches) {
}
