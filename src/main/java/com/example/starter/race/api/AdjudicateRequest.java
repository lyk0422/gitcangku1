package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 三源检查点显式裁定请求：三条当前读数齐全且最大减最小不超过20毫秒时，
 * 取中位值写入原分段计时；否则422整次不写。
 *
 * @param adjudicationId  全局唯一裁定ID（第二层幂等键）
 * @param checkpointCode  启用三源模式的检查点代码
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record AdjudicateRequest(
        @NotBlank String adjudicationId,
        @NotBlank String checkpointCode,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
