package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 恢复赛事请求：对处于 SUSPENDED 的同一 eventKey 提交严格更大的恢复时刻，
 * 在同一事务内一致重算全部选手净分段、净完赛、漏点与排名版本。
 *
 * @param eventKey        待恢复的中止事件键
 * @param resumeElapsedMs 恢复时刻（毫秒，1~86400000），必须严格大于登记的 startElapsedMs
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ResumeRaceRequest(
        @NotBlank String eventKey,
        @NotNull @Positive @Max(86_400_000L) Long resumeElapsedMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
