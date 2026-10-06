package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交三源检查点传感器读数请求（每选手每点最多三个不同 sensorId，修订号初始1）。
 *
 * @param checkpointCode  启用三源模式的检查点代码
 * @param sensorId        传感器ID，同一选手同一检查点下唯一
 * @param elapsedMillis   该传感器上报的累计耗时（毫秒，1~86400000）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SubmitSensorReadingRequest(
        @NotBlank String checkpointCode,
        @NotBlank String sensorId,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
