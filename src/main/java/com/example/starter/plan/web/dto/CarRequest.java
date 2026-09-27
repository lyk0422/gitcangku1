package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 危险品编组车厢登记项：车厢位置与危险品类别。
 */
public record CarRequest(
        @NotNull @Min(0) @Max(9999) Integer carPosition,
        @NotBlank @Size(max = 64) String category) {
}
