package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 来源策略版本视图，coordinates 按名称升序。
 */
public record PolicyResponse(
        String lockfileName,
        int version,
        Instant createdAt,
        List<PolicyCoordinateView> coordinates) {
}
