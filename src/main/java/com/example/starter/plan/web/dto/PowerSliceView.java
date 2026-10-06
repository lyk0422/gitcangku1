package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 恒定功率时间片视图，区间左闭右开；totalPowerKw 为片内累计功率（千瓦）。
 */
public record PowerSliceView(Instant startUtc, Instant endUtc, long totalPowerKw) {
}
