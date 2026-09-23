package com.example.starter.evidence.dto;

import com.example.starter.evidence.PackageStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 组合包只读视图：包信息 + 全部借出明细 + 剩余未归还集合 + 全部归还批次（含逐件保管链）。
 * 列表均稳定排序：明细与剩余集合按证物键升序，批次按批次号升序。
 *
 * @param packageKey  组合包业务键
 * @param caseKey     案件键
 * @param custodianId 借出期间不变的保管点
 * @param borrowerId  借用人
 * @param handlerId   经办经办人
 * @param purpose     借出用途
 * @param loanAt      实际借出时刻（UTC）
 * @param dueAt       UTC 到期时刻
 * @param status      组合包状态
 * @param closedAt    自动关闭时刻（UTC）；null 表示未关闭
 * @param items       全部借出明细（按证物键升序）
 * @param remaining   剩余未归还证物键集合（按证物键升序）
 * @param batches     全部归还批次（按批次号升序，含逐件保管链）
 */
public record PackageView(
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
        List<PackageItemView> items,
        List<String> remaining,
        List<ReturnBatchView> batches) {
}
