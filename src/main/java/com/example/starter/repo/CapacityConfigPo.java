package com.example.starter.repo;

/**
 * 时空桶容量配置记录。
 *
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC），15 分钟对齐
 * @param maxFlights  最大航班占用数，>= 0；未配置的桶按 0 处理
 * @param updatedAt   最近配置时间，epoch 毫秒（UTC）
 */
public record CapacityConfigPo(int cellX, int cellY, long bucketStart,
                               int maxFlights, long updatedAt) {
}
