package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双案封存快照实体，对应 case_transfer_snapshot 表。
 * 每件证物每个 transferId 固定 SOURCE/TARGET 各一行，只追加，撤销不删除。
 *
 * @param id               主键
 * @param transferId       所属移交主键（transferId）
 * @param evidenceKey      证物业务键
 * @param side             快照方
 * @param caseKey          该侧案件键
 * @param location         移出前原库位；null 表示未登记库位
 * @param sealNo           封签版本（封条编号）
 * @param orderVersion     令版本
 * @param lastLoanId       移交前最后一笔借出记录 id 水位；null 表示此前无借出
 * @param lastInspectionId 移交前最后一条封签核验记录 id 水位；null 表示此前无核验
 * @param lastHandoffId    移交前最后一条双人保管链交接 id 水位；null 表示此前无交接
 * @param createdAt        快照生成时刻（UTC，微秒精度）
 */
public record CaseTransferSnapshot(
        Long id,
        Long transferId,
        String evidenceKey,
        SnapshotSide side,
        String caseKey,
        String location,
        String sealNo,
        String orderVersion,
        Long lastLoanId,
        Long lastInspectionId,
        Long lastHandoffId,
        LocalDateTime createdAt) {
}
