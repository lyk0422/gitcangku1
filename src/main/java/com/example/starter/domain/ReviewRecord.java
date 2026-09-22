package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 审核结果持久化记录：不可变，结论与命中区域按提交时的一致快照计算并永久保留。
 */
public record ReviewRecord(
        String reviewId,
        String routeId,
        int routeVersion,
        long airspaceVersion,
        Conclusion conclusion,
        List<String> hitZoneIds,
        Instant createdAt) {
}
