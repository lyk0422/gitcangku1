package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 认证快照（不可变）：认证成功时写入，记录认证后重算的设备累计工时与保养判定。
 *
 * @param certificationId                   认证记录自增主键
 * @param requestId                         认证请求的 certKey（幂等键）
 * @param equipmentId                       所属设备标识
 * @param readingId                         被认证的读数标识
 * @param revisionNo                        被认证的读数修订号
 * @param certifiedBy                       认证人（须不同于录入人）
 * @param cumulativeMinutes                 被认证读数的累计工时（分钟）
 * @param latestCertifiedCumulativeMinutes  认证后设备最新已认证累计工时（分钟）
 * @param runMinutes                        认证后本轮运行分钟
 * @param maintenanceStatus                 认证后保养判定：OK 或 DUE
 * @param equipmentVersion                  认证后的设备版本号
 * @param certifiedAt                       认证时刻（UTC）
 */
public record CertificationSnapshot(
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
