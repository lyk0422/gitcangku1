package com.example.starter.sampling;

import java.time.Instant;

/**
 * 采样观测：对应 sampling_observation 表的一行，permitKey 为提交幂等键。
 *
 * @param permitKey      提交幂等键：设备+许可版本+网格+时刻+观测内容的 SHA-256 指纹
 * @param deviceId       观测设备标识
 * @param gridX          网格X坐标
 * @param gridY          网格Y坐标
 * @param collectedAtUtc 采集时刻（UTC）
 * @param content        观测内容
 * @param permitId       命中的许可标识
 * @param permitVersion  命中许可的版本号
 * @param status         观测状态：RECEIVED 已接收未合并 / MERGED 已合并 / PERMIT_REVIEW 许可撤销待复核
 * @param batchId        并入的批量合并标识；单次提交为 null
 * @param clusterId      所属冲突簇标识；无冲突为 null
 * @param responseBody   首次成功响应体（JSON 原文），用于同键重放
 */
public record SamplingObservation(
        String permitKey,
        String deviceId,
        int gridX,
        int gridY,
        Instant collectedAtUtc,
        String content,
        String permitId,
        int permitVersion,
        ObservationStatus status,
        String batchId,
        String clusterId,
        String responseBody) {
}
