package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记赛事中止事件请求：裁判登记中止开始时刻、受影响起始检查点与事件键，赛事转为 SUSPENDED。
 *
 * @param eventKey        中止事件键，赛事内唯一
 * @param checkpointKey   受影响起始检查点代码（已通过该检查点的选手补偿0）
 * @param startElapsedMs  中止开始的比赛相对耗时（毫秒，1~86400000）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SuspendRaceRequest(
        @NotBlank String eventKey,
        @NotBlank String checkpointKey,
        @NotNull @Positive @Max(86_400_000L) Long startElapsedMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
