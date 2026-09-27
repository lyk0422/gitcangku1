package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手退赛请求；退赛者不占组别容量、不参与组别排名，已退赛者的重分申请批准返回409。
 *
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
