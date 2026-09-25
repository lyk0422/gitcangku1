package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手起跑请求。
 *
 * @param startId         起跑记录业务键（幂等键），同键同参重放、异参409
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record StartRunnerRequest(
        @NotBlank String startId,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
