package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 修订三源检查点某传感器读数请求（仅 OPEN 赛事可提交）。
 *
 * <p>成功后赛事版本与该传感器修订号同时推进，该检查点旧裁定立即失效并
 * 从即时排名视图排除，须以三条当前修订号读数重新裁定。
 *
 * @param checkpointCode         启用三源模式的检查点代码
 * @param sensorId               被修订的传感器ID
 * @param elapsedMillis          修订后的传感器累计读数（毫秒，1~86400000）
 * @param operator               修订操作者
 * @param expectedRaceVersion    客户端所见赛事版本
 * @param expectedSensorRevision 客户端所见该传感器读数修订号
 * @param requestId              全局唯一请求ID（写操作幂等键）
 */
public record ReviseSensorSampleRequest(
        @NotBlank String checkpointCode,
        @NotBlank String sensorId,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotBlank String operator,
        @NotNull Integer expectedRaceVersion,
        @NotNull Integer expectedSensorRevision,
        @NotBlank String requestId
) {
}
