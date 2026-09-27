package com.example.starter.blind.dto;

/**
 * 分配/退组普通视图：只含无含义盲码、区组号、参与者编号与在组状态。
 * 严禁包含处理代码 treatment 或可直接解码的席位号 seatNo/随机表序号 seqNo。
 *
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param blindCode     随机无含义盲码
 * @param blockNo       区组号（不含区组内席位序号）
 * @param status        ASSIGNED / WITHDRAWN
 * @param assignedAt    分配时间，Unix 毫秒，UTC
 * @param withdrawnAt   退组时间，Unix 毫秒，UTC；null 表示未退组
 * @param tableVersion  分配时固化的随机表版本号；null 表示分配时区组尚未封存
 */
public record AllocationView(
        String experimentId,
        String participantId,
        String blindCode,
        int blockNo,
        String status,
        long assignedAt,
        Long withdrawnAt,
        Integer tableVersion
) {
}
