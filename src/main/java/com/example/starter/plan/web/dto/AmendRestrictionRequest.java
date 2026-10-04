package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 限速令修订请求：按 expectedVersion 乐观校验，成功版本加一。
 * 修订不改写已生成的重排记录。requestKey 为幂等键。
 */
public record AmendRestrictionRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        Integer maxSpeedKmh,
        @NotBlank String operator) {
}