package com.example.starter.evidence.dto;

import com.example.starter.evidence.SnapshotSide;

import java.time.LocalDateTime;

/**
 * 双案封存快照视图。固化双方案件、原位置、封签版本与令版本，后续操作不回写。
 *
 * @param transferId   所属移交主键
 * @param evidenceKey  证物业务键
 * @param side         SOURCE 来源案件移出快照 / TARGET 目标案件移入快照
 * @param caseKey      该侧案件键
 * @param location     移出前原库位；null 表示未登记库位
 * @param sealNo       封签版本（封条编号）
 * @param orderVersion 令版本
 * @param createdAt    快照生成时刻（UTC，微秒精度）
 */
public record SnapshotView(
        long transferId,
        String evidenceKey,
        SnapshotSide side,
        String caseKey,
        String location,
        String sealNo,
        String orderVersion,
        LocalDateTime createdAt) {
}
