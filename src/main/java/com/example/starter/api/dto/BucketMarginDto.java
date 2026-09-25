package com.example.starter.api.dto;

/**
 * 时空桶容量余量（按完整后态计算，含未参与航线的占用）。
 *
 * @param cellId      空域单元标识
 * @param bucketStart 时间桶起始，epoch 毫秒（UTC）
 * @param maxFlights  容量上限；null 表示未配置（不限容量）
 * @param beforeCount 转配前占用数
 * @param afterCount  转配后占用数
 * @param margin      转配后余量（maxFlights - afterCount）；不限容量时为 null
 */
public record BucketMarginDto(String cellId, long bucketStart, Integer maxFlights,
                              int beforeCount, int afterCount, Integer margin) {
}
