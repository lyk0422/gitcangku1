package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 三源计时裁定请求：对某选手某三源检查点的三条当前读数取中位值，
 * 显式裁定后写入原分段计时；缺共识或违反分段不变量返回422且整次不写。
 *
 * @param adjudicationId  全局唯一裁定ID（第二层幂等键），同时作为写入分段计时的 timingId
 * @param checkpointCode  启用三源模式的检查点代码
 * @param operator        裁定操作者
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record AdjudicateSensorRequest(
        @NotBlank String adjudicationId,
        @NotBlank String checkpointCode,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
