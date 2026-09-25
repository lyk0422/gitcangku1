package com.example.starter.api.dto;

/**
 * 时空容量桶视图。
 *
 * @param bucketKey      规范化时空桶键 cellX:cellY:windowStartMin:windowEndMin
 * @param cellX          空间单元 X 索引
 * @param cellY          空间单元 Y 索引
 * @param windowStartMin 时间窗起始（epoch 分钟，UTC）
 * @param windowEndMin   时间窗结束（epoch 分钟，UTC）
 * @param capacity       容量上限
 * @param used           当前占用数（含已起飞）
 * @param remaining      剩余容量（capacity - used）
 */
public record CapacityBucketDto(String bucketKey, int cellX, int cellY,
                                long windowStartMin, long windowEndMin,
                                int capacity, int used, int remaining) {
}
