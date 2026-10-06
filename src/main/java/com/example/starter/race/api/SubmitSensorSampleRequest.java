package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记三源检查点传感器读数请求。
 *
 * @param sampleId        全局唯一采样记录ID（第二层幂等键）
 * @param checkpointCode  启用三源模式的检查点代码
 * @param sensorId        传感器ID，同一选手同一检查点须为三个不同传感器
 * @param elapsedMillis   传感器累计读数（毫秒，1~86400000）
 * @param operator        采样登记操作者
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SubmitSensorSampleRequest(
        @NotBlank String sampleId,
        @NotBlank String checkpointCode,
        @NotBlank String sensorId,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
