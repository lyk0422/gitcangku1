package com.example.starter.domain;

/**
 * 禁飞区：zoneId 唯一的非退化轴对齐闭矩形，只能创建或撤销。
 *
 * @param zoneId 禁飞区唯一标识
 * @param minX   矩形最小 X（米，闭区间）
 * @param minY   矩形最小 Y（米，闭区间）
 * @param maxX   矩形最大 X（米，闭区间，大于 minX）
 * @param maxY   矩形最大 Y（米，闭区间，大于 minY）
 * @param active 是否有效；已撤销的禁飞区不参与后续审核
 */
public record Zone(String zoneId, int minX, int minY, int maxX, int maxY, boolean active) {
}
