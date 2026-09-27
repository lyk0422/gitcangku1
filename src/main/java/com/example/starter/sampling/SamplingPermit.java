package com.example.starter.sampling;

import java.time.Instant;

/**
 * 采样许可：对应 sampling_permit 表的一行。
 *
 * @param permitId        许可唯一标识
 * @param deviceId        观测设备标识
 * @param gridMinX        网格范围最小X（含），采样网格全局坐标
 * @param gridMaxX        网格范围最大X（含）
 * @param gridMinY        网格范围最小Y（含）
 * @param gridMaxY        网格范围最大Y（含）
 * @param validFromUtc    有效期起点（UTC，左闭）
 * @param validToUtc      有效期终点（UTC，右开）；提前结束时前移
 * @param maxObservations 最大观测数上限
 * @param usedCount       已接收观测计数，不超过上限
 * @param permitVersion   许可版本号，提交观测时需携带并一致
 * @param status          许可状态：ACTIVE 有效 / ENDED 已提前结束 / REVOKED 已撤销
 */
public record SamplingPermit(
        String permitId,
        String deviceId,
        int gridMinX,
        int gridMaxX,
        int gridMinY,
        int gridMaxY,
        Instant validFromUtc,
        Instant validToUtc,
        int maxObservations,
        int usedCount,
        int permitVersion,
        PermitStatus status) {
}
