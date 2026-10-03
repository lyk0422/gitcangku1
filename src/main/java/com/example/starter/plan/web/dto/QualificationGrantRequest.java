package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 授予乘务资格请求。requestKey 为幂等键；资格区间左闭右开，起止均为 UTC 时刻；
 * 同一乘务员再次授予时旧版本自动作废，版本递增。
 */
public record QualificationGrantRequest(
        @NotBlank String requestKey,
        @NotBlank String memberCode,
        @Min(1) @Max(9) int lineGrade,
        @NotNull Instant validFromUtc,
        @NotNull Instant validToUtc) {
}