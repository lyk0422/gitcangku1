package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 发布固化的站停快照视图：在到发时刻之外，原样返回发布当时的站点版本与规则，
 * 规则后续修订不改变本视图。
 */
public record PublishedStopView(String trainNo, String stationId,
                                Instant arrivalUtc, Instant departureUtc,
                                int stationVersion, int minDwellMinutes, int platformCapacity,
                                long publishedAt) {
}
