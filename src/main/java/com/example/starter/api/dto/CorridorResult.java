package com.example.starter.api.dto;

/**
 * 走廊结果。
 *
 * @param corridorId 走廊唯一标识
 * @param xMin       左边界（含），米
 * @param yMin       下边界（含），米
 * @param xMax       右边界（含），米
 * @param yMax       上边界（含），米
 * @param capacity   当前同时容量上限
 */
public record CorridorResult(String corridorId, int xMin, int yMin, int xMax, int yMax,
                             int capacity) {
}
