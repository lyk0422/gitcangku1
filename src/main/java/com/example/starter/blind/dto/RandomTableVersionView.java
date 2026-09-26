package com.example.starter.blind.dto;

/**
 * 随机表版本视图：只含摘要、容量、处理代码集合与计数，不含具体序列。
 *
 * @param versionId         版本主键
 * @param experimentId      实验编号
 * @param blockNo           区组号
 * @param versionNo         区组内版本号，从 1 开始
 * @param capacity          该版本覆盖的区组累计容量
 * @param treatmentCodes    处理代码集合，逗号分隔
 * @param tableDigest       该版本完整序列的 SHA-256 摘要（hex）
 * @param predecessorVersionId 前驱版本主键；null 表示初始版本
 * @param sealed            该版本是否已封存
 * @param allocatedCount    区组已分配席位数（含已退组）
 * @param remainingCount    区组未分配席位数（capacity - allocatedCount）
 * @param createdAt         版本生成时间，Unix 毫秒，UTC
 */
public record RandomTableVersionView(
        long versionId,
        String experimentId,
        int blockNo,
        int versionNo,
        int capacity,
        String treatmentCodes,
        String tableDigest,
        Long predecessorVersionId,
        boolean sealed,
        long allocatedCount,
        long remainingCount,
        long createdAt
) {
}
