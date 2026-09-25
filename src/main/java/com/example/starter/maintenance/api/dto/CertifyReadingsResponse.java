package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量认证结果：含每条读数的认证结果与每台设备重算后的累计工时及保养判定。
 * 同 certKey 成功重放时完整返回本结果。
 *
 * @param requestId   认证请求的 certKey
 * @param certifier   认证人
 * @param certifiedAt 认证时刻（UTC）
 * @param readings    已认证读数明细（按设备与采样时刻升序）
 * @param equipments  每台受影响设备的重算结果
 */
public record CertifyReadingsResponse(
        String requestId,
        String certifier,
        Instant certifiedAt,
        List<CertifiedReading> readings,
        List<EquipmentRecompute> equipments) {

    /**
     * 已认证读数明细。
     *
     * @param certificationId   认证快照标识
     * @param equipmentId       设备标识
     * @param readingId         读数标识
     * @param revisionNo        被认证的修订号
     * @param cumulativeMinutes 被认证的累计工时（分钟）
     */
    public record CertifiedReading(
            long certificationId,
            String equipmentId,
            String readingId,
            int revisionNo,
            long cumulativeMinutes) {
    }

    /**
     * 单台设备认证后的重算结果。
     *
     * @param equipmentId                      设备标识
     * @param version                          认证后的设备版本号
     * @param latestCertifiedCumulativeMinutes 最新已认证累计工时（分钟），无已认证读数时为 0
     * @param runMinutes                       本轮运行分钟（最新已认证工时 - 最近保养锚点工时）
     * @param maintenanceStatus                保养判定：OK 或 DUE
     */
    public record EquipmentRecompute(
            String equipmentId,
            long version,
            long latestCertifiedCumulativeMinutes,
            long runMinutes,
            String maintenanceStatus) {
    }
}
