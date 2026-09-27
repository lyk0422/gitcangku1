package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 解除能源隔离令请求：须对所有尚未解除的锁定点逐一给出解除原因，先校验完整最终状态再单事务提交。
 * 仅确认所有锁定点逐一解除后令才转为 RELEASED；部分解除（集合不完整）返回 422 且不产生任何变更。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param operator   解除操作者（逐点固化）
 * @param reason     令级解除原因（非空，随隔离令固化）
 * @param releases   本次解除的锁定点集合；须恰好等于全部未解除锁定点，重复/缺失/多余均 422
 */
public record ReleaseIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String reason,
        @NotEmpty @Valid List<@Valid LockPointReleaseInput> releases) {
}
