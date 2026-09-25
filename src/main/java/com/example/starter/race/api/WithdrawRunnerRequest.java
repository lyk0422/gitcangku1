package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手退赛请求：退赛后该选手不参与排名，且不再是“有效候选人”；
 * 已被冲线证据引用的选手退赛后，相关证据裁决时返回422。
 *
 * @param operator         退赛操作者标识
 * @param expectedVersion  客户端所见赛事版本
 * @param requestId        全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
