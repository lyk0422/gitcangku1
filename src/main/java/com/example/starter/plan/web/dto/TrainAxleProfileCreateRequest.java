package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建列车轴重档案请求。requestId 为全局幂等键，operator 为必填操作者；
 * 轴序列 2 至 16 轴，每轴为正整数千克。
 */
public record TrainAxleProfileCreateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull @Size(min = 2, max = 16) List<@NotNull @Positive Long> axleWeightsKg) {
}
