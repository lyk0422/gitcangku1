package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换列车轴重档案请求，expectedVersion 必须与当前版本一致；
 * 轴序列 2 至 16 轴，每轴为正整数千克，轴序换位视为改参。
 */
public record TrainAxleProfileUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 2, max = 16) List<@NotNull @Positive Long> axleWeightsKg) {
}
