package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 认证快照视图（不可变）：认证成功时写入，含认证后重算的累计工时与保养判定。
 *
 * @param certificationId                   认证记录标识
 * @param requestId                         认证请求的 certKey
 * @param equipmentId                       设备标识
 * @param readingId                         被认证的读数标识
 * @param revisionNo                        被认证的修订号
 * @param certifiedBy                       认证人
 * @param cumulativeMinutes                 被认证读数的累计工时（分钟）
 * @param latestCertifiedCumulativeMinutes  认证后设备最新已认证累计工时（分钟）
 * @param runMinutes                        认证后本轮运行分钟
 * @param maintenanceStatus                 认证后保养判定：OK 或 DUE
 * @param equipmentVersion                  认证后的设备版本号
 * @param certifiedAt                       认证时刻（UTC）
 */
public record CertificationSnapshotView(
        long certificationId,
        String requestId,
        String equipmentId,
        String readingId,
        int revisionNo,
        String certifiedBy,
        long cumulativeMinutes,
        long latestCertifiedCumulativeMinutes,
        long runMinutes,
        String maintenanceStatus,
        long equipmentVersion,
        Instant certifiedAt) {
}
