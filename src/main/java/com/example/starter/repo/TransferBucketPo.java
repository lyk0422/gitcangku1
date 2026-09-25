package com.example.starter.repo;

/**
 * 转配涉及时空桶的前后占用冻结记录。
 *
 * @param transferKey 所属转配单标识
 * @param cellId      空域单元标识
 * @param bucketStart 15 分钟 UTC 时间桶起点，epoch 秒
 * @param usedBefore  转配前该桶占用架次（全部航线合计）
 * @param usedAfter   转配后该桶占用架次（全部航线合计）
 * @param maxFlights  转配时容量上限；null 表示该桶未配置上限
 */
public record TransferBucketPo(String transferKey, String cellId, long bucketStart,
                               int usedBefore, int usedAfter, Integer maxFlights) {
}
