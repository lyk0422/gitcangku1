package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 站台配置登记/修订请求。requestKey 为幂等键；
 * 首次登记 expectedVersion 必须为 null，修订必须等于当前版本，成功后版本加一。
 */
public record StationConfigRequest(
        @NotBlank String requestKey,
        @NotNull @PositiveOrZero @Max(100000) Integer minDwellMinutes,
        @NotNull @PositiveOrZero @Max(100000) Integer platformCapacity,
        Integer expectedVersion,
        String operator) {
}
