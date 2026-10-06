package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 寻径发布计划的路径分段视图，区间左闭右开。
 */
public record RouteLegView(int seq, String segmentId, String fromStation, String toStation,
                           Instant startUtc, Instant endUtc) {
}
