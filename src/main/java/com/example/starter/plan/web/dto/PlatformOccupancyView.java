package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 站台占用视图：已发布计划在该站台上的占用窗口（计划区段占用的整体跨度，左闭右开）。
 */
public record PlatformOccupancyView(String scheduleKey, String platformCode, Integer consistLength,
                                    Instant startUtc, Instant endUtc) {
}
