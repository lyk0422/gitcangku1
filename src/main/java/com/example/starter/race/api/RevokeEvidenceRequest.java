package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤回未裁决冲线证据请求：仅 PENDING 证据可撤回，撤回保留撤回记录；
 * 已裁决证据不可撤回（409）。
 *
 * @param operator         撤回操作者标识
 * @param expectedVersion  客户端所见赛事版本
 * @param requestId        全局唯一请求ID（幂等键）
 */
public record RevokeEvidenceRequest(
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
