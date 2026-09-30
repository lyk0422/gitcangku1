package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 裁决申诉请求：ACCEPT-全额退回保证金，REJECT-全部没收；两者均为终态。
 * 对已终态申诉重复裁决或改判返回409，且不会再次退款或没收。
 *
 * @param decision        裁决结果：ACCEPT / REJECT
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record AdjudicateProtestRequest(
        @NotBlank String decision,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
