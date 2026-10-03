package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手退赛请求；退赛后禁止提交分段与更换芯片，成绩状态为 WITHDRAWN。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param operator        操作者（计入幂等指纹）
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String operator,
        @NotBlank String requestId
) {
}