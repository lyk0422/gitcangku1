package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 站台占用视图：已发布计划在该站台的停靠时段（左闭右开，按开始时刻升序）。
 */
public record PlatformOccupancyView(String scheduleKey, String platformCode,
                                    Instant startUtc, Instant endUtc) {
}
