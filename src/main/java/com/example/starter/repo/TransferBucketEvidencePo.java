package com.example.starter.repo;

/**
 * 转配桶级冻结证据：转配时容量配置与转配前后全量占用数。
 *
 * @param transferKey 转配单标识
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC）
 * @param maxFlights  转配时配置的容量上限；未配置记 0
 * @param usedBefore  转配前全量占用数（含未参与航线）
 * @param usedAfter   转配后全量占用数（含未参与航线）
 */
public record TransferBucketEvidencePo(String transferKey, int cellX, int cellY, long bucketStart,
                                       int maxFlights, int usedBefore, int usedAfter) {
}
