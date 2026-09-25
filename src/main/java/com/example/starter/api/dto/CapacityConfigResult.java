package com.example.starter.api.dto;

/**
 * 容量配置结果。
 *
 * @param cellId      空域单元标识
 * @param bucketStart 时间桶起始，epoch 毫秒（UTC）
 * @param maxFlights  当前生效的最大航班占用数
 */
public record CapacityConfigResult(String cellId, long bucketStart, int maxFlights) {
}
