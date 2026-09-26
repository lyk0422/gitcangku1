package com.example.starter.blind.dto;

/**
 * 封存记录视图：只含表摘要、区组容量、处理代码集合与封存时刻；
 * 不包含 sealKey，不包含具体序列。
 *
 * @param sealId         封存记录主键
 * @param experimentId   实验编号
 * @param blockNo        区组号
 * @param versionId      被封存的随机表版本主键
 * @param tableDigest    封存时表摘要（SHA-256 hex）
 * @param capacity       封存时区组累计容量
 * @param treatmentCodes 封存时处理代码集合，逗号分隔
 * @param sealedActor    执行封存的操作者编号
 * @param sealedAt       封存时刻，Unix 毫秒，UTC
 */
public record SealView(
        long sealId,
        String experimentId,
        int blockNo,
        long versionId,
        String tableDigest,
        int capacity,
        String treatmentCodes,
        String sealedActor,
        long sealedAt
) {
}
