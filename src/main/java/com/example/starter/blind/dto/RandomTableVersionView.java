package com.example.starter.blind.dto;

/**
 * 随机表版本视图（明细/历史）：只含表摘要、容量、处理代码集合与时刻，
 * 不含具体序列与封存密钥。
 *
 * @param experimentId       实验编号
 * @param blockNo            区组号
 * @param versionNo          版本号，区组内从 1 开始递增
 * @param tableDigest        随机表摘要 SHA-256 十六进制
 * @param capacity           本版本覆盖的累计容量（最大席位序号）
 * @param addedSeats         本版本相对前序版本新增的席位数
 * @param treatmentCodes     处理代码集合（去重排序，如 AB）
 * @param predecessorVersion 显式引用的已封存前序版本号；版本 1 为 null
 * @param status             SEALED=已封存可分配；CLOSED=未分配余量已关闭
 * @param sealedAt           封存（生成）时刻，Unix 毫秒，UTC
 * @param closedAt           余量关闭时刻，Unix 毫秒，UTC；null 表示未关闭
 */
public record RandomTableVersionView(
        String experimentId,
        int blockNo,
        int versionNo,
        String tableDigest,
        int capacity,
        int addedSeats,
        String treatmentCodes,
        Integer predecessorVersion,
        String status,
        long sealedAt,
        Long closedAt
) {
}
