package com.example.starter.domain;

/**
 * 时空桶：空域单元 + 15 分钟 UTC 时间桶，是容量占用与转配的最小单位。
 *
 * @param cellId      空域单元标识，格式 C<gx>_<gy>
 * @param bucketStart 15 分钟 UTC 时间桶起始时刻，epoch 毫秒（UTC），对齐 900000 毫秒
 */
public record Bucket(String cellId, long bucketStart) {
}
