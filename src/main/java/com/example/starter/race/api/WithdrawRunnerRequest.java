package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 选手退赛请求；退赛后状态 WITHDRAWN，不排名，不可开始或恢复医疗暂停。
 *
 * @param reason          退赛原因（可选，登记后固化）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record WithdrawRunnerRequest(
        @Size(max = 512) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
