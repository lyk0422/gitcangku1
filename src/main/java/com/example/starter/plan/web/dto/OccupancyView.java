package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开；routeId 为 null 表示不参与进路互斥。
 */
public record OccupancyView(String trainNo, String sectionId, String routeId,
                            Instant startUtc, Instant endUtc) {
}