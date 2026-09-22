package com.example.starter.baggage.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登记航段请求。
 *
 * @param requestId   全局唯一请求标识（幂等键）
 * @param legId       航段标识，全局唯一
 * @param origin      始发站
 * @param destination 到达站
 */
public record RegisterLegRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "legId 不能为空") String legId,
        @NotBlank(message = "origin 不能为空") String origin,
        @NotBlank(message = "destination 不能为空") String destination) {
}
