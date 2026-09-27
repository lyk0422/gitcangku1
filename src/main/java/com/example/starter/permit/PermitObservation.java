package com.example.starter.permit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 许可观测实体：对应 permit_observation 表一行；接收时绑定唯一命中许可的版本快照。
 *
 * @param observationKey 观测业务唯一键（设备|采集时刻|网格点稳定拼接）
 * @param deviceId       采集设备标识
 * @param gridLon        采集网格点经度
 * @param gridLat        采集网格点纬度
 * @param sampledAt      采集时刻（UTC）
 * @param permitId       接收时唯一命中的许可标识
 * @param permitVersion  命中许可版本号快照
 * @param content        观测内容原文
 * @param reviewStatus   RECEIVED / MERGED / PERMIT_REVIEW
 * @param mergedClusterId 合并所属冲突簇标识；未合并为 null
 * @param rejectReasonCode 最近拒绝原因码；成功接收为 null
 * @param receivedAt     接收完成时刻（UTC）
 */
public record PermitObservation(
        String observationKey,
        String deviceId,
        BigDecimal gridLon,
        BigDecimal gridLat,
        Instant sampledAt,
        String permitId,
        int permitVersion,
        String content,
        String reviewStatus,
        String mergedClusterId,
        String rejectReasonCode,
        Instant receivedAt) {
}
