package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 变更赛事排名模式请求；仅尚无分段记录的 OPEN 赛事可变更，成功后版本加一。
 *
 * @param rankingMode     目标排名模式：NORMAL / COUNTBACK
 * @param expectedVersion 客户端所见赛事版本
 * @param operator        操作者标识，非空
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SetRankingModeRequest(
        @NotBlank String rankingMode,
        @NotNull Integer expectedVersion,
        @NotBlank String operator,
        @NotBlank String requestId
) {
}
