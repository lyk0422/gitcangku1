package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 危险品车厢登记项：车厢位置从 1 开始，同一计划内唯一。
 */
public record HazardCarRequest(
        @NotNull @Min(1) @Max(999) Integer carPosition,
        @NotBlank String hazardClass) {
}
