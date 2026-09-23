package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 恢复中止事件请求；resumeElapsedMs 必须大于该事件的 startElapsedMs。
 * 恢复在一致状态中重算全部选手净分段、净完赛、漏点与排名版本；
 * 任一净不变量被破坏则整体回滚，事件不落库。
 *
 * @param resumeElapsedMs 恢复累计耗时点（毫秒，1~86400000），必须大于中止开始点
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record ResumeRaceRequest(
        @NotNull @Positive @Max(86_400_000L) Long resumeElapsedMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
