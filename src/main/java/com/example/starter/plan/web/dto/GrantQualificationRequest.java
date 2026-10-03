package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 授予乘务资格请求。requestKey 为幂等键；资格区间 [validFromUtc, validToUtc)
 * 左闭右开，须完整覆盖计划行程方可发布。
 */
public record GrantQualificationRequest(
        @NotBlank String requestKey,
        @NotBlank String crewMemberId,
        @Min(1) int lineGrade,
        @NotNull Instant validFromUtc,
        @NotNull Instant validToUtc) {
}