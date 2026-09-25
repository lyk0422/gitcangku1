package com.example.starter.api.dto;

/**
 * 容量配置结果。
 *
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC）
 * @param maxFlights  最大航班占用数
 */
public record CapacityConfigResult(
        Integer cellX,
        Integer cellY,
        Long bucketStart,
        Integer maxFlights) {
}
