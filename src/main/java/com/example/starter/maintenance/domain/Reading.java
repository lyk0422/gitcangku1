package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeMinutes  当前累计工时（分钟）
 * @param revisionNo         当前修订号，初始 1
 * @param status             认证状态：PENDING（不参与累计工时与保养判定）/ CERTIFIED
 * @param recordedBy         当前修订版本的录入人
 * @param certifiedBy        当前修订版本的认证人，未认证为 null
 * @param certifiedAt        当前修订版本的认证时刻（UTC），未认证为 null
 */
public record Reading(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo,
        String status,
        String recordedBy,
        String certifiedBy,
        Instant certifiedAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CERTIFIED = "CERTIFIED";
}
