package com.example.starter.plan.model;

/**
 * 线路图有向区段。
 *
 * @param segmentId   区段编号，同一图版本内唯一
 * @param fromStation 起点站
 * @param toStation   终点站
 * @param minutes     行驶分钟，正整数
 */
public record GraphSegment(String segmentId, String fromStation, String toStation, int minutes) {
}
