package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 更新列车有序轴重档案请求，expectedVersion 必须与当前版本一致；
 * 若更新使任何已发布计划非法则 409，版本不变。轴序换位视为改参。
 */
public record TrainAxleProfileUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 2, max = 16) List<@NotNull @Positive Integer> axleWeightsKg) {
}
