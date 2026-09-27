package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 危险品编组车厢登记项：某列车编组中 position 位置的车厢编挂 hazardClass 类危险品。
 * 位置从 1 开始；同一列车位置重复在发布/改签校验时判 422。
 */
public record HazardCarRequest(
        @NotBlank String trainNo,
        @NotNull @Min(1) Integer position,
        @NotBlank String hazardClass) {
}
