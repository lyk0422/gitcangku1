package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 限速令登记请求。requestKey 为幂等键；指纹包含区段、时段、速度与操作者。
 * sectionId/startUtc/endUtc/maxSpeedKmh 的非法取值由服务层判定并返回 422。
 */
public record RegisterRestrictionRequest(
        @NotBlank String requestKey,
        @NotBlank String restrictionKey,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        Integer maxSpeedKmh,
        @NotBlank String operator) {
}