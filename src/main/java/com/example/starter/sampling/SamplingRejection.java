package com.example.starter.sampling;

import java.time.Instant;

/**
 * 采样拒绝记录：对应 sampling_rejection 表的一行，追加式、不回溯改写。
 *
 * @param rejectionId    拒绝记录自增标识
 * @param scope          来源：SINGLE 单次提交 / BATCH 批量合并
 * @param requestKey     提交幂等键（permitKey）或批次标识（batchId）
 * @param deviceId       观测设备标识；许可级失败为许可所属设备
 * @param gridX          网格X坐标；许可级失败为 null
 * @param gridY          网格Y坐标；许可级失败为 null
 * @param collectedAtUtc 采集时刻（UTC）；许可级失败为 null
 * @param reason         拒绝原因码（见 {@link SamplingReasons}）
 * @param message        拒绝详情
 * @param rejectedAtUtc  拒绝时刻（UTC）
 */
public record SamplingRejection(
        long rejectionId,
        String scope,
        String requestKey,
        String deviceId,
        Integer gridX,
        Integer gridY,
        Instant collectedAtUtc,
        String reason,
        String message,
        Instant rejectedAtUtc) {
}
