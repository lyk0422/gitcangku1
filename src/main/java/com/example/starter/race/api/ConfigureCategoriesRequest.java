package com.example.starter.race.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 一次性配置赛事组别请求（配置后不可修改）。
 *
 * @param categories      组别定义列表，1~10个，代码赛事内唯一
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfigureCategoriesRequest(
        @NotEmpty @Valid List<CategoryDefinition> categories,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {

    /**
     * 单个组别定义。
     *
     * @param categoryCode 组别代码，赛事内唯一
     * @param capacity     组别容量（在赛人数上限，>=1）
     */
    public record CategoryDefinition(
            @NotBlank String categoryCode,
            @NotNull @Positive Integer capacity
    ) {
    }
}