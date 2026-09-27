package com.example.starter.blind.dto;

/**
 * 分配明细行（区组版本视角）：只暴露固化使用的随机表版本号，
 * 不暴露版本内序号与处理代码，避免间接还原具体序列。
 *
 * @param experimentId 实验编号
 * @param participantId 合成参与者编号
 * @param blindCode    随机无含义盲码
 * @param blockNo      区组号
 * @param versionNo    本次分配固化使用的随机表版本号
 * @param status       ASSIGNED / WITHDRAWN
 * @param assignedAt   分配时间，Unix 毫秒，UTC
 * @param withdrawnAt  退组时间，Unix 毫秒，UTC；null 表示未退组
 */
public record AllocationDetailView(
        String experimentId,
        String participantId,
        String blindCode,
        int blockNo,
        int versionNo,
        String status,
        long assignedAt,
        Long withdrawnAt
) {
}
