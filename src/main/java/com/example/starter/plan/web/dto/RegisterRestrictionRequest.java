package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 登记气象限速令请求。requestKey 为幂等键；operator 可选，参与幂等指纹。
 * 区段、时段与速度的语义校验在服务层完成，非法时返回 422。
 */
public record RegisterRestrictionRequest(
        @NotBlank String requestKey,
        String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull Integer maxSpeedKmh,
        String operator) {
}
