package com.example.starter.repo;

/**
 * 转配前后受影响时空桶的余量冻结记录（不可变）。
 *
 * @param transferKey 所属转配单标识
 * @param cellId      受影响空域单元标识
 * @param bucketStart 受影响时间桶起始，epoch 毫秒（UTC）
 * @param maxFlights  转配时容量上限；null 表示未配置（不限容量）
 * @param beforeCount 转配前占用数（含未参与航线）
 * @param afterCount  转配后占用数（含未参与航线）
 */
public record TransferBucketPo(String transferKey, String cellId, long bucketStart,
                               Integer maxFlights, int beforeCount, int afterCount) {
}
