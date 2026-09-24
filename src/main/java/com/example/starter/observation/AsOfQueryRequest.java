package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 按时刻一致视图查询请求：给定 UTC 时刻与 1～50 个 observationId。
 *
 * @param asOfUtc       目标 UTC 时刻（ISO-8601）；晚于服务端当前时刻返回 400
 * @param observationIds 待查询的观测记录标识集合，允许重复（按升序去重后返回），数量 1～50
 */
public record AsOfQueryRequest(
        @NotNull Instant asOfUtc,
        @NotEmpty @Size(min = 1, max = 50) List<@NotBlank @Size(max = 64) String> observationIds) {
}
