package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记分组中止事件请求；成功后赛事转为 SUSPENDED，仅允许恢复。
 *
 * @param eventKey        全局唯一中止事件ID
 * @param checkpointKey   受影响起始检查点代码：中止开始前已通过该检查点的选手补偿0
 * @param startElapsedMs  中止开始累计耗时点（毫秒，1~86400000），须不小于此前全部恢复点
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SuspendRaceRequest(
        @NotBlank String eventKey,
        @NotBlank String checkpointKey,
        @NotNull @Positive @Max(86_400_000L) Long startElapsedMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
