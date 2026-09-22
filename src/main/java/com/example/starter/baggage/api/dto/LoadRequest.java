package com.example.starter.baggage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量装载请求。
 *
 * @param requestId       全局唯一请求标识（幂等键）
 * @param expectedVersion 航段期望版本号（乐观并发控制）
 * @param bagTags         待装载行李牌号（1~20 个、不重复）
 */
public record LoadRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "expectedVersion 不能为空")
        @PositiveOrZero(message = "expectedVersion 不能为负") Integer expectedVersion,
        @NotNull(message = "bagTags 不能为空")
        @Size(min = 1, max = 20, message = "单批装载行李数量须为 1~20") List<@NotBlank(message = "行李牌号不能为空") String> bagTags) {
}
