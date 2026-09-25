package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提前结束抑制区间请求。仅已开始（当前时刻 &gt;= 生效起始）的 ACTIVE 区间可提前结束，
 * 结束时刻不得早于当前时刻，且必须早于当前生效结束时刻。
 *
 * @param requestId 写操作全局唯一幂等键
 * @param endAtUtc  新的生效结束时刻（不含），epoch 毫秒，UTC
 */
public record EndSuppressionIntervalRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long endAtUtc
) {
}
