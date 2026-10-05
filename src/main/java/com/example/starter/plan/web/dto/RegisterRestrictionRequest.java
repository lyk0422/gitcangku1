package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 限速令登记请求。requestKey 为幂等键；restrictionKey 为限速令业务键（客户端提供，全局唯一）；
 * 时段为 UTC 左闭右开区间；maxSpeedKmh 合法范围 10～300（越界返回 422）。
 */
public record RegisterRestrictionRequest(
        @NotBlank String requestKey,
        @NotBlank String restrictionKey,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer maxSpeedKmh,
        @NotBlank String operator) {
}
