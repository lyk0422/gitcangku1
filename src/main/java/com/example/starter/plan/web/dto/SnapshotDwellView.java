package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 发布快照中固化的站停视图。字段语义与 rail_snapshot_station_dwell 一致，
 * 规则修订后历史查询仍返回发布时固化的版本、规则值与实际停留分钟。
 */
public record SnapshotDwellView(String trainNo, String stationId,
                                Instant arrivalUtc, Instant departureUtc,
                                int stationVersion, int minDwellMinutes, int platformCapacity,
                                int actualDwellMinutes) {
}
