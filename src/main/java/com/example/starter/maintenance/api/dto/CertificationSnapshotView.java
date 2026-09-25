package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 认证快照视图（不可变）。
 *
 * @param snapshotId               快照自增主键
 * @param certKey                  批次认证幂等键
 * @param equipmentId              所属设备标识
 * @param readingId                被认证读数标识
 * @param revisionNo               被认证的读数修订号
 * @param recordedBy               该版本录入人
 * @param certifiedBy              认证人
 * @param cumulativeMinutes        被认证读数的累计工时（分钟）
 * @param batchSeq                 规范化批次内序号（从 1 开始）
 * @param latestCumulativeMinutes  认证后设备最新已认证累计工时（分钟）
 * @param runMinutes               认证后本轮运行分钟
 * @param dueStatus                认证后保养判定：OK / DUE
 * @param certifiedAt              认证时刻（UTC）
 */
public record CertificationSnapshotView(
        long snapshotId,
        String certKey,
        String equipmentId,
        String readingId,
        int revisionNo,
        String recordedBy,
        String certifiedBy,
        long cumulativeMinutes,
        int batchSeq,
        long latestCumulativeMinutes,
        long runMinutes,
        String dueStatus,
        Instant certifiedAt) {
}
