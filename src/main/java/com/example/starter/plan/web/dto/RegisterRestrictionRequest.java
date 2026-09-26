package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 登记气象限速令请求。requestKey 为幂等键；指纹包含限速参数与操作者。
 * 区段、时段与速度的合法性由业务层校验，非法返回 422。
 */
public record RegisterRestrictionRequest(
        @NotBlank String requestKey,
        @NotBlank String restrictionKey,
        @NotNull String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer maxSpeedKmh,
        @NotBlank String operator) {
}
