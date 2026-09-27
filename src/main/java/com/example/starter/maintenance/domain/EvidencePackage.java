package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养证据包版本（不可变）。每次提交生成递增版本，固化周期、模板版本与提交人。
 *
 * @param packageId       证据包自增主键
 * @param equipmentId     所属设备标识
 * @param cycleNo         保养周期序号
 * @param packageVersion  证据包版本号，同一设备周期内从 1 递增
 * @param templateId      提交时设备保养模板标识（快照）
 * @param templateVersion 提交时模板版本号（快照），后续模板修订不改写
 * @param submittedBy     提交人（维护人员）
 * @param submittedAt     提交时刻（UTC）
 */
public record EvidencePackage(long packageId, String equipmentId, long cycleNo, int packageVersion,
                              String templateId, int templateVersion, String submittedBy,
                              Instant submittedAt) {
}
