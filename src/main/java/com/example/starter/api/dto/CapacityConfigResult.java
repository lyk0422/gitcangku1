package com.example.starter.api.dto;

/**
 * 容量配置结果。
 *
 * @param cellId      空域单元标识
 * @param bucketStart 15 分钟 UTC 时间桶起点，epoch 秒
 * @param maxFlights  生效的最大航班数
 */
public record CapacityConfigResult(String cellId, long bucketStart, int maxFlights) {
}
