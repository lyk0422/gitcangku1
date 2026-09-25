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
 * @param certStatus         认证状态：PENDING / CERTIFIED；仅 CERTIFIED 参与累计工时与保养判定
 * @param recordedBy         当前版本录入人；录入人不得认证自己的读数
 * @param certifiedBy        认证人；null 表示未认证
 * @param certifiedAt        认证时刻（UTC）；null 表示未认证
 */
public record Reading(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo,
        String certStatus,
        String recordedBy,
        String certifiedBy,
        Instant certifiedAt) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CERTIFIED = "CERTIFIED";
}
