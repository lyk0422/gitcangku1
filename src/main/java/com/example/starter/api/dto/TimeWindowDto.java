package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 可选 UTC 毫秒半开时间窗口 [startUtcMillis, endUtcMillis)。
 * 起止必须成对提供且起点严格早于终点；不提供窗口对象表示全时有效。
 *
 * @param startUtcMillis 窗口起点（含），UTC 毫秒
 * @param endUtcMillis   窗口终点（不含），UTC 毫秒
 */
public record TimeWindowDto(
        @NotNull Long startUtcMillis,
        @NotNull Long endUtcMillis) {
}
