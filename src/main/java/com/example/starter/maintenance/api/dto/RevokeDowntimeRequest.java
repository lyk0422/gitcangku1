package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤销停机区间请求。撤销后扣减量不再参与计算，原区间记录保留且不可改写。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 */
public record RevokeDowntimeRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion) {
}
