package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建单条访客抑制区间请求。区间为 UTC 半开 [startAtUtc, endAtUtc)。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param visitorId  被抑制访客编号
 * @param startAtUtc 生效开始时刻，epoch 毫秒，UTC，左闭
 * @param endAtUtc   生效结束时刻，epoch 毫秒，UTC，右开
 */
public record CreateSuppressionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotNull Long startAtUtc,
        @NotNull Long endAtUtc
) {
}
