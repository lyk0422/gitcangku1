package com.example.starter.incident;

import java.time.Instant;

/**
 * 联合指挥交接单实体，对应 joint_handovers 表，handoverKey 全局唯一。
 * closureKeys 为闭包事件键的有序 JSON 数组字符串（按事件键排序）；
 * frozenSummary 为冻结摘要 JSON（每事件指挥人/状态、OPEN 任务版本状态及排序依赖、
 * 未确认升级版本）；handoverVersion 为冻结摘要的 SHA-256。
 * acceptedAt 仅 ACCEPTED 有值。时间均为 UTC。
 */
public record JointHandover(
        long id,
        String handoverKey,
        String fromCommander,
        String toCommander,
        HandoverStatus status,
        String handoverVersion,
        String closureKeys,
        String frozenSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant acceptedAt) {
}
