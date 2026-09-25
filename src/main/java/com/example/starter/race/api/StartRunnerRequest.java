package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手起跑请求；强制检录赛事须先持有未过期 PASS，否则 422 且不写入。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record StartRunnerRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
