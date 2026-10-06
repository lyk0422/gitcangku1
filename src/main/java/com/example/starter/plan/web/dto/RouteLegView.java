package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 寻径发布结果中的路径段视图，起止为 UTC 时刻，区间左闭右开。
 */
public record RouteLegView(String sectionId, String fromStation, String toStation,
                           int travelMinutes, Instant startUtc, Instant endUtc) {
}
