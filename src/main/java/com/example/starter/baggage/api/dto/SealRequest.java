package com.example.starter.baggage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 封舱请求。
 *
 * @param requestId       全局唯一请求标识（幂等键）
 * @param expectedVersion 航段期望版本号（乐观并发控制）
 */
public record SealRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "expectedVersion 不能为空")
        @PositiveOrZero(message = "expectedVersion 不能为负") Integer expectedVersion) {
}
