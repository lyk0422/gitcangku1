package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤回冲线证据请求；仅未裁决（PENDING）证据可撤回，撤回后保留撤回记录。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawFinishEvidenceRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
