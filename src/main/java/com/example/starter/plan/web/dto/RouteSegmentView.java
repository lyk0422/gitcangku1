package com.example.starter.plan.web.dto;

/**
 * 线路图区段视图。
 */
public record RouteSegmentView(String segmentId, String fromStation, String toStation, int minutes) {
}
