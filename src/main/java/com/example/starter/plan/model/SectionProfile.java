package com.example.starter.plan.model;

/**
 * 线路区段档案：限速判定与整体顺延的基准参数。
 *
 * @param sectionId         区段 ID，全局唯一
 * @param designSpeedKmh    线路既有设计速度 km/h，计划按此速度铺画；限速低于该值即判定违反
 * @param minHeadwayMinutes 线路既有最小间隔（分钟），整体顺延的步进单位
 */
public record SectionProfile(String sectionId, int designSpeedKmh, int minHeadwayMinutes) {
}
