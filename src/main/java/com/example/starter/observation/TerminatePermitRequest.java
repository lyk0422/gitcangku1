package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 许可提前结束请求：实际有效上界变为 terminatedAtUtc（不含），只影响该时刻之后的采样。
 *
 * @param requestId       全局唯一请求标识（幂等去重键）
 * @param terminatedAtUtc 提前结束时刻（UTC，不含）；必须晚于服务端当前时刻且不早于许可起点、不晚于原定终点
 */
public record TerminatePermitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Instant terminatedAtUtc) {
}
