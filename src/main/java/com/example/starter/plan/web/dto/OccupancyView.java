package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开。
 */
public record OccupancyView(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
}
