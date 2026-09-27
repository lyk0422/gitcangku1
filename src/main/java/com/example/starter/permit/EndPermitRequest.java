package com.example.starter.permit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 许可提前结束请求：仅影响结束时刻之后的采样，不删除已接收观测。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param endAt     提前结束生效时刻（UTC，右开）；为 null 时取服务端当前时刻
 */
public record EndPermitRequest(
        @NotBlank @Size(max = 128) String requestId,
        Instant endAt) {
}
