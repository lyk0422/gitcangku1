package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建单条抑制区间请求。生效区间为 UTC 左闭右开 [validFromUtc, validUntilUtc)。
 *
 * @param requestId     写操作全局唯一幂等键
 * @param visitorId     合成访客编号
 * @param validFromUtc  生效起始时刻（含），epoch 毫秒，UTC
 * @param validUntilUtc 生效结束时刻（不含），epoch 毫秒，UTC；必须大于起始时刻
 */
public record CreateSuppressionIntervalRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotNull Long validFromUtc,
        @NotNull Long validUntilUtc
) {
}
