package com.example.starter.plan.model;

/**
 * 线路图有向区段（按图版本冻结）。
 *
 * @param segmentId   区段编号，版本内唯一，同时作为占用区段 ID
 * @param fromStation 起点站
 * @param toStation   终点站
 * @param minutes     行驶分钟，正整数
 */
public record RouteSegment(String segmentId, String fromStation, String toStation, int minutes) {
}
