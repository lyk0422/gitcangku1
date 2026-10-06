package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建列车有序轴重档案请求。requestId 为全局幂等键，operator 为操作者（非空）；
 * 轴重 2 至 16 轴，每轴正整数千克，顺序即轴序。
 */
public record TrainAxleProfileCreateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String trainNo,
        @NotNull @Size(min = 2, max = 16) List<@NotNull @Positive Integer> axleWeightsKg) {
}
