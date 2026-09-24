package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 按时刻一致视图查询请求：给定 UTC 时刻与 1～50 个观测记录标识（只读，不推进任何版本）。
 *
 * @param asOfUtc       查询基准 UTC 时刻；晚于服务端当前时刻返回 400
 * @param observationIds 观测记录标识集合，1～50 个；服务端按升序去重后回放，时刻前尚未创建的记录按 ABSENT 返回
 */
public record AsOfQueryRequest(
        @NotNull Instant asOfUtc,
        @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> observationIds) {
}
