package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 证据包版本。不可变；周期内版本号递增，固化提交人与模板版本。
 *
 * @param packageId       证据包主键
 * @param equipmentId     所属设备标识
 * @param cycleNo         所属周期号
 * @param packageVersion  证据包版本号，周期内从 1 递增
 * @param templateVersion 提交时设备当前模板版本快照
 * @param submittedBy     证据提交人（维护人员），不可与完工确认人为同一人
 * @param submittedAt     提交时刻（UTC）
 * @param requestId       产生该证据包的请求 requestId
 */
public record EvidencePackage(
        long packageId,
        String equipmentId,
        int cycleNo,
        int packageVersion,
        int templateVersion,
        String submittedBy,
        Instant submittedAt,
        String requestId) {
}
