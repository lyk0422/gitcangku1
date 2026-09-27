package com.example.starter.blind.dto;

/**
 * 随机表封存视图：只含表摘要、区组容量、处理代码集合与封存时刻，
 * 不包含具体序列、序号或任何可解码盲底的信息。
 *
 * @param experimentId   实验编号
 * @param blockNo        区组号，从1开始
 * @param versionNo      被封存的随机表版本号
 * @param sealKey        封存密钥（研究侧持有，全局唯一）
 * @param sequenceDigest 随机表摘要（SHA-256 十六进制）
 * @param blockCapacity  封存时的区组容量，单位：个
 * @param treatmentCodes 处理代码集合（字典序升序逗号分隔），如 A,B
 * @param sealedAt       封存时刻，Unix 毫秒，UTC
 */
public record TableSealView(
        String experimentId,
        int blockNo,
        int versionNo,
        String sealKey,
        String sequenceDigest,
        int blockCapacity,
        String treatmentCodes,
        long sealedAt
) {
}
