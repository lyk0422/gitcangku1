package com.example.starter.baggage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 到达确认请求。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param bagTags   实际到达行李牌号集合，须与封舱清单完全相同（可为空集合对应空清单）
 */
public record ArrivalRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "bagTags 不能为空") List<@NotBlank(message = "行李牌号不能为空") String> bagTags) {
}
