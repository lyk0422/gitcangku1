package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开；fromStation/toStation 为 null 表示未登记站点。
 */
public record OccupancyView(String trainNo, String sectionId, String fromStation, String toStation,
                            Instant startUtc, Instant endUtc) {
}
