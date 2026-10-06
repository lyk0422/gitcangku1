package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 分层密度估计请求中的一层：正整数总体面积加 1 至 5 个不同观测的当前版本。
 *
 * @param totalArea 该层总体面积，正整数，单位平方米
 * @param members   该层引用的观测成员（1 至 5 个，层内及跨层均不得重复）
 */
public record EstimateLayerRequest(
        @NotNull @Min(1) Integer totalArea,
        @NotNull @Size(min = 1, max = 5) List<@Valid EstimateMemberRequest> members) {
}
