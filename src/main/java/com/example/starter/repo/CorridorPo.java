package com.example.starter.repo;

/**
 * 航路走廊持久化记录。
 *
 * @param corridorId 走廊唯一标识
 * @param xMin       左边界（含），米
 * @param yMin       下边界（含），米
 * @param xMax       右边界（含），米
 * @param yMax       上边界（含），米
 * @param capacity   同时容量上限（1~50），仅可上调
 * @param createdAt  创建时间（epoch 毫秒，UTC）
 */
public record CorridorPo(String corridorId, int xMin, int yMin, int xMax, int yMax,
                         int capacity, long createdAt) {
}
