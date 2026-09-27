package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 证据包材料批次摘要（不可变；同一设备同一周期对同一批次仅一条）。
 *
 * @param id              自增主键
 * @param equipmentId     所属设备标识
 * @param cycleNo         所属保养周期序号
 * @param batchCode       材料批次编码
 * @param addedVersion    该摘要首次进入证据包时的版本号
 * @param quantity        使用数量（正整数，单位见 unit）
 * @param unit            数量单位
 * @param submittedBy     摘要提交人
 * @param createdAt       摘要提交时刻（UTC）
 */
public record EvidenceMaterial(
        long id,
        String equipmentId,
        int cycleNo,
        String batchCode,
        int addedVersion,
        long quantity,
        String unit,
        String submittedBy,
        Instant createdAt) {
}
