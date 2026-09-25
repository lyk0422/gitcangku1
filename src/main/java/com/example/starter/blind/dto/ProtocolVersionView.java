package com.example.starter.blind.dto;

/**
 * 协议版本视图：版本号、规范化比例、生效时刻、状态与操作者审计字段；不含席位映射盲底。
 *
 * @param experimentId 实验编号
 * @param version      协议版本号，从 1 开始
 * @param ratioA       A 组区组比例（百分比）
 * @param ratioB       B 组区组比例（百分比）
 * @param effectiveAt  生效时刻，Unix 毫秒，UTC
 * @param status       PENDING / EFFECTIVE / SUPERSEDED / REVOKED
 * @param createdBy    创建该版本的操作者编号
 * @param createdAt    创建时间，Unix 毫秒，UTC
 * @param revokedAt    撤销时间，Unix 毫秒，UTC；null 表示未撤销
 * @param revokedBy    撤销操作者编号；null 表示未撤销
 * @param supersededAt 被新版本取代时间，Unix 毫秒，UTC；null 表示未被取代
 */
public record ProtocolVersionView(
        String experimentId,
        int version,
        int ratioA,
        int ratioB,
        long effectiveAt,
        String status,
        String createdBy,
        long createdAt,
        Long revokedAt,
        String revokedBy,
        Long supersededAt
) {
}
