package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 修订三源检查点传感器读数请求：仅 OPEN 赛事可提交，
 * 须同时携带赛事版本与当前读数修订号；成功后两者都推进，旧裁定立即失效。
 *
 * @param checkpointCode          启用三源模式的检查点代码
 * @param sensorId                被修订读数的传感器ID
 * @param elapsedMillis           修订后的累计耗时（毫秒，1~86400000）
 * @param expectedRaceVersion     客户端所见赛事版本
 * @param expectedSensorRevision  客户端所见读数修订号
 * @param requestId               全局唯一请求ID（写操作幂等键）
 */
public record ReviseSensorReadingRequest(
        @NotBlank String checkpointCode,
        @NotBlank String sensorId,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotNull Integer expectedRaceVersion,
        @NotNull Integer expectedSensorRevision,
        @NotBlank String requestId
) {
}
