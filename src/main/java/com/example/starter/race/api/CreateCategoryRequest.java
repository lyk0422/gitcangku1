package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 新建组别请求。
 *
 * @param categoryCode    组别代码，赛事内唯一
 * @param capacity        组别容量：在赛（未退赛）选手数上限，>=1
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record CreateCategoryRequest(
        @NotBlank String categoryCode,
        @NotNull @Positive Integer capacity,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}