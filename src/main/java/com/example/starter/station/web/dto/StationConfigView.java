package com.example.starter.station.web.dto;

/**
 * 站台当前配置视图。
 */
public record StationConfigView(String stationId, int minDwellMinutes, int platformCapacity,
                                int version, String operatorId, long createdAt, long updatedAt) {
}
