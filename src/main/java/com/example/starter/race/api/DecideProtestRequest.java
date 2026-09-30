package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 申诉裁决请求；ACCEPT 全额退回保证金，REJECT 全部没收，两者均为终态。
 *
 * @param decision        裁决结果：ACCEPT / REJECT
 * @param reason          裁决理由（可空，最长500字）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record DecideProtestRequest(
        @NotBlank String decision,
        @Size(max = 500) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
