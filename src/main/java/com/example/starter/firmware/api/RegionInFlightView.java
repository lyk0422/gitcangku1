package com.example.starter.firmware.api;

/**
 * 区域当前进行中（已下发未完成）任务数视图。maxInFlight 为 null 表示该区域未配置上限。
 */
public record RegionInFlightView(long releaseId, String region, long inFlight, Integer maxInFlight) {
}
