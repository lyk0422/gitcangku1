package com.example.starter.repo;

/**
 * 走廊当前状态。
 *
 * @param corridorId 走廊唯一标识
 * @param xMin       矩形左边界（含），单位米
 * @param yMin       矩形下边界（含），单位米
 * @param xMax       矩形右边界（含），单位米
 * @param yMax       矩形上边界（含），单位米
 * @param capacity   同时容量上限（1～50），只能上调
 * @param touch      仅用于事务内加行级排他锁的计数器，无业务含义
 * @param createdAt  创建时间，epoch 毫秒（UTC）
 * @param updatedAt  最近变更时间，epoch 毫秒（UTC）
 */
public record CorridorPo(String corridorId, int xMin, int yMin, int xMax, int yMax,
                         int capacity, long touch, long createdAt, long updatedAt) {
}
