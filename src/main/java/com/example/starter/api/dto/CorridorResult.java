package com.example.starter.api.dto;

/**
 * 走廊操作结果。
 *
 * @param corridorId 走廊唯一标识
 * @param xMin       矩形左边界（含），单位米
 * @param yMin       矩形下边界（含），单位米
 * @param xMax       矩形右边界（含），单位米
 * @param yMax       矩形上边界（含），单位米
 * @param capacity   当前同时容量上限（1～50）
 */
public record CorridorResult(String corridorId, int xMin, int yMin, int xMax, int yMax,
                             int capacity) {
}
