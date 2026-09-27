package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 选手退赛登记请求（不可变）。
 *
 * @param reason          退赛原因，1~256字符
 * @param officialRole    登记退赛的裁判角色标识
 * @param occurredAt      退赛生效的绝对UTC毫秒时间戳，作为排序与裁决依据
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @NotBlank @Size(max = 256) String reason,
        @NotBlank @Size(max = 64) String officialRole,
        @NotNull Long occurredAt,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
