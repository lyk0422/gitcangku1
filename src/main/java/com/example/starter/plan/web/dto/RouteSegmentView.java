package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 寻径发布结果的逐段时刻视图。区间左闭右开。
 */
public record RouteSegmentView(String segmentId, String fromStation, String toStation,
                               Instant startUtc, Instant endUtc) {
}
