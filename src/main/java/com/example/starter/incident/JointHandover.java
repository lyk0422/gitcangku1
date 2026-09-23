package com.example.starter.incident;

import java.time.Instant;

/**
 * 联合指挥交接单实体，对应 joint_handovers 表，handoverKey 全局唯一。
 * handoverVersion 为预览冻结摘要的 SHA-256 版本号；
 * submittedIncidentKeys/closureIncidentKeys 为 JSON 数组字符串；
 * frozenSummary 为冻结完整摘要 JSON；acceptedAt 仅 ACCEPTED 有值。
 */
public record JointHandover(
        long id,
        String handoverKey,
        String fromCommander,
        String toCommander,
        HandoverStatus status,
        String handoverVersion,
        String submittedIncidentKeys,
        String closureIncidentKeys,
        String frozenSummary,
        Instant acceptedAt,
        Instant createdAt,
        Instant updatedAt) {
}
