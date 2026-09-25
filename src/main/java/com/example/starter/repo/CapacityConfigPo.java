package com.example.starter.repo;

/**
 * 时空桶容量配置记录。
 *
 * @param cellId      空域单元标识
 * @param bucketStart 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC）
 * @param maxFlights  该桶允许的最大航班占用数
 */
public record CapacityConfigPo(String cellId, long bucketStart, int maxFlights) {
}
