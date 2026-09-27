package com.example.starter.sampling;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 许可提前结束请求：有效期终点前移至指定时刻，只影响之后采样，不删除已接收观测。
 *
 * @param endedAtUtc 新的有效期终点（UTC，右开），必须落在原 (validFromUtc, validToUtc) 区间内
 */
public record EndPermitRequest(
        @NotNull Instant endedAtUtc) {
}
