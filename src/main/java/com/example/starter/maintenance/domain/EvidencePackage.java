package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 证据包头（设备某保养周期内的一个不可变版本）。
 *
 * @param equipmentId      所属设备标识
 * @param cycleNo          保养周期序号，从 1 开始；完成保养后进入下一周期
 * @param packageVersion   周期内证据包版本号，从 1 开始递增
 * @param templateVersion  该周期固化的模板版本（周期内首个证据包版本确定）
 * @param submittedBy      该版本提交人（维护人员标识），不可兼任完工确认人
 * @param createdAt        证据包版本生成时刻（UTC）
 */
public record EvidencePackage(
        String equipmentId,
        int cycleNo,
        int packageVersion,
        int templateVersion,
        String submittedBy,
        Instant createdAt) {
}
