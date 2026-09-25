package com.example.starter.api.dto;

/**
 * 时空桶占用与余量视图。
 *
 * @param cellId         空域单元标识
 * @param bucketStart    15 分钟 UTC 时间桶起点，epoch 秒
 * @param usedBefore     转配前占用架次（全部航线合计）
 * @param usedAfter      转配后占用架次（全部航线合计）
 * @param maxFlights     容量上限；null 表示该桶未配置上限
 * @param remainingAfter 转配后剩余容量；未配置上限时为 null
 */
public record BucketUsageDto(String cellId, long bucketStart,
                             int usedBefore, int usedAfter,
                             Integer maxFlights, Integer remainingAfter) {
}
