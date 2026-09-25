package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次认证响应：完整重算结果。同 certKey 成功重放时原样返回。
 *
 * @param certKey        批次认证幂等键
 * @param certifiedBy    认证人标识
 * @param certifiedCount 本批认证读数条数
 * @param certifiedAt    认证时刻（UTC）
 * @param results        按设备分组的重算结果（按设备标识升序）
 */
public record CertifyBatchResponse(
        String certKey,
        String certifiedBy,
        int certifiedCount,
        Instant certifiedAt,
        List<EquipmentCertResult> results) {

    /**
     * 单台设备认证后的重算结果。
     *
     * @param equipmentId              设备标识
     * @param certifiedReadings        本批该设备被认证的读数（按采样时刻升序）
     * @param latestCumulativeMinutes  认证后最新已认证累计工时（分钟）
     * @param runMinutes               认证后本轮运行分钟
     * @param status                   认证后保养判定：OK / DUE
     * @param equipmentVersion         认证后的设备版本号
     */
    public record EquipmentCertResult(
            String equipmentId,
            List<CertifiedReadingView> certifiedReadings,
            long latestCumulativeMinutes,
            long runMinutes,
            String status,
            long equipmentVersion) {
    }

    /**
     * 被认证读数视图。
     *
     * @param readingId          读数标识
     * @param revisionNo         被认证的修订号
     * @param cumulativeMinutes  累计工时（分钟）
     */
    public record CertifiedReadingView(
            String readingId,
            int revisionNo,
            long cumulativeMinutes) {
    }
}
