package com.example.starter.repo;

/**
 * 容量配置记录。
 *
 * @param cellId      空域单元标识，格式 gx:gy
 * @param bucketStart 15 分钟 UTC 时间桶起点，epoch 秒
 * @param maxFlights  该时空桶最大航班数（占用上限）
 * @param updatedAt   最近配置时间，epoch 毫秒（UTC）
 */
public record CapacityConfigPo(String cellId, long bucketStart, int maxFlights, long updatedAt) {
}
