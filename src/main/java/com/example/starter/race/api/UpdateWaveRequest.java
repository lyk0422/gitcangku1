package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 修改单个波次请求：可调整起跑时刻与参赛者集合；修改后重新校验全部已计时参赛者，
 * 任一违反整次 422。waveKey 由路径指定，不在请求体内。
 *
 * @param startMs         新的波次 UTC 起跑时刻，Unix 毫秒时间戳
 * @param bibs            新的参赛者参赛号集合；同一参赛者只能属于一个波次
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record UpdateWaveRequest(
        @NotNull @Positive Long startMs,
        @NotNull List<@NotBlank String> bibs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
