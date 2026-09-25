package com.example.starter.blind.dto;

/**
 * 协议版本视图；只含比例、生效时刻与状态，不含席位排列或盲码内容。
 *
 * @param experimentId     实验编号
 * @param versionNo        协议版本号，从 1 开始
 * @param ratioA           区组内处理 A 的比例
 * @param ratioB           区组内处理 B 的比例
 * @param effectiveAt      计划生效时刻，Unix 毫秒，UTC
 * @param status           PENDING / ACTIVE / REVOKED
 * @param createdByActor   创建修订的操作者编号
 * @param createdAt        修订提交时间，Unix 毫秒，UTC
 * @param effectiveEventAt 实际裁决生效时间，Unix 毫秒，UTC；null 表示尚未生效
 * @param revokedAt        撤销时间，Unix 毫秒，UTC；null 表示未撤销
 */
public record ProtocolVersionView(
        String experimentId,
        int versionNo,
        int ratioA,
        int ratioB,
        long effectiveAt,
        String status,
        String createdByActor,
        long createdAt,
        Long effectiveEventAt,
        Long revokedAt
) {
}
