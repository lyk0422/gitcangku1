package com.example.starter.sampling;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 提交观测响应：返回幂等键、命中许可与接收后计数。
 *
 * @param permitKey         提交幂等键
 * @param deviceId          观测设备标识
 * @param gridX             网格X坐标
 * @param gridY             网格Y坐标
 * @param collectedAtUtc    采集时刻（UTC）
 * @param permitId          命中的许可标识
 * @param permitVersion     命中许可的版本号
 * @param observationStatus 接收后的观测状态
 * @param usedCount         接收后许可已用计数；批量合并条目为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitResponse(
        String permitKey,
        String deviceId,
        int gridX,
        int gridY,
        Instant collectedAtUtc,
        String permitId,
        int permitVersion,
        String observationStatus,
        Integer usedCount) {
}
