package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤销处罚请求（按全局 penaltyId 定位）。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RevokePenaltyRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
