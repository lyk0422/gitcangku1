package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 保养证据包版本视图（不可变快照）。检查项按规范排序，材料按批次编码升序。
 *
 * @param packageId       证据包标识
 * @param equipmentId     所属设备标识
 * @param cycleNo         保养周期序号
 * @param packageVersion  证据包版本号
 * @param templateId      提交时模板标识（快照）
 * @param templateVersion 提交时模板版本号（快照）
 * @param submittedBy     提交人
 * @param submittedAt     提交时刻（UTC）
 * @param items           检查项结果列表（规范排序）
 * @param materials       材料批次摘要列表
 * @param equipmentVersion 提交后的设备版本号（历史查询时为 0，无意义）
 */
public record EvidencePackageResponse(
        long packageId,
        String equipmentId,
        long cycleNo,
        int packageVersion,
        String templateId,
        int templateVersion,
        String submittedBy,
        Instant submittedAt,
        List<EvidenceItemView> items,
        List<EvidenceMaterialView> materials,
        long equipmentVersion) {
}
