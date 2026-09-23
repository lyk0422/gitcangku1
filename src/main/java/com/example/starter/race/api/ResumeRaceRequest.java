package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 恢复中止事件请求：提交更大的 resumeElapsedMs，重算全部选手净计时后赛事回到 OPEN。
 *
 * @param resumeElapsedMs 恢复累计耗时（毫秒，1~86400000），必须大于中止开始耗时
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record ResumeRaceRequest(
        @NotNull @Positive @Max(86_400_000L) Long resumeElapsedMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
