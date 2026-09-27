package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 证据包历史摘要（不含检查项与材料明细）。
 *
 * @param packageId       证据包标识
 * @param equipmentId     所属设备标识
 * @param cycleNo         保养周期序号
 * @param packageVersion  证据包版本号
 * @param templateId      提交时模板标识（快照）
 * @param templateVersion 提交时模板版本号（快照）
 * @param submittedBy     提交人
 * @param submittedAt     提交时刻（UTC）
 * @param itemCount       检查项结果条数
 * @param materialCount   材料摘要条数
 */
public record EvidencePackageSummary(
        long packageId,
        String equipmentId,
        long cycleNo,
        int packageVersion,
        String templateId,
        int templateVersion,
        String submittedBy,
        Instant submittedAt,
        int itemCount,
        int materialCount) {
}
