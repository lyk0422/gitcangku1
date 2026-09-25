package com.example.starter.repo;

/**
 * 时空容量桶记录。
 *
 * @param bucketKey      规范化时空桶键 cellX:cellY:windowStartMin:windowEndMin
 * @param cellX          空间单元 X 索引
 * @param cellY          空间单元 Y 索引
 * @param windowStartMin 时间窗起始（epoch 分钟，UTC）
 * @param windowEndMin   时间窗结束（epoch 分钟，UTC）
 * @param capacity       容量上限（可同时占用航线数）
 * @param createdAt      创建时间（epoch 毫秒）
 */
public record CapacityBucketPo(String bucketKey, int cellX, int cellY,
                               long windowStartMin, long windowEndMin,
                               int capacity, long createdAt) {
}
