package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 登记气象限速令请求。requestKey 为幂等键；operator 为操作者标识（可空），参与幂等指纹。
 * 时段为 UTC 左闭右开；maxSpeedKmh 合法范围 10～300，越界返回 422。
 */
public record RestrictionRegisterRequest(
        @NotBlank String requestKey,
        @NotBlank String restrictionKey,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer maxSpeedKmh,
        String operator) {
}
