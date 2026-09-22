package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 审核结果：不可变历史记录。conclusion 为 CLEAR 或 BLOCKED；
 * hitZoneIds 为命中的禁飞区 zoneId，按字典序去重，CLEAR 时为空列表。
 */
public record ReviewResponse(
        String reviewId,
        String routeId,
        int routeVersion,
        long airspaceVersion,
        String conclusion,
        List<String> hitZoneIds,
        Instant createdAt) {
}
