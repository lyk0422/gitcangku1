package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 提交选手检查点通过记录请求。
 *
 * @param timingId        全局唯一分段记录ID（第二层幂等键）
 * @param checkpointCode  通过的检查点代码
 * @param elapsedMillis   通过累计耗时（毫秒，1~86400000），必须小于该选手原始完赛耗时
 * @param chipCode        产生该读数的芯片代码；可空时按选手芯片时间线在读取时刻唯一推导
 * @param readAtMs        芯片实际读取的 UTC 时刻（毫秒）；可空表示未上报，按服务端接收时刻判定归属
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SubmitTimingRequest(
        @NotBlank String timingId,
        @NotBlank String checkpointCode,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @Size(max = 128) String chipCode,
        @Positive Long readAtMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {

    /** 兼容不上报芯片与读取时刻的提交：chipCode/readAtMs 视为 null。 */
    public SubmitTimingRequest(
            String timingId,
            String checkpointCode,
            Long elapsedMillis,
            Integer expectedVersion,
            String requestId) {
        this(timingId, checkpointCode, elapsedMillis, null, null, expectedVersion, requestId);
    }
}
