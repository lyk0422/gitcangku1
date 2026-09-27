package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 提前结束许可请求：将有效期结束时刻提前到指定 UTC 时刻，只影响之后采样，不删除已接收观测。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param endAtUtc  新的有效期结束时刻（UTC，不含），必须严格位于原有效期内部
 */
public record EndPermitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Instant endAtUtc) {
}
