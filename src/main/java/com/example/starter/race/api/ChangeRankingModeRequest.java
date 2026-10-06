package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 变更排名模式请求；仅尚无计时数据（无分段记录且无已计时选手）的 OPEN 赛事可变更，
 * 成功后赛事版本加一。
 *
 * @param rankingMode     目标排名模式：TOTAL_TIME / COUNTBACK
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ChangeRankingModeRequest(
        @NotBlank String rankingMode,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
