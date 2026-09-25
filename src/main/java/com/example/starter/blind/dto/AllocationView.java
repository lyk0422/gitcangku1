package com.example.starter.blind.dto;

/**
 * 分配/退组普通视图：只含无含义盲码、区组号、参与者编号、中心与协议版本归属及在组状态。
 * 严禁包含处理代码 treatment 或可直接解码的席位号 seatNo。
 *
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param blindCode     随机无含义盲码
 * @param blockNo       区组号（不含区组内席位序号）；中心协议登记为 null
 * @param centerId      中心协议登记所属中心编号；实验级区组登记为 null
 * @param versionNo     分配时固化的协议版本号；实验级区组登记为 null
 * @param status        ASSIGNED / WITHDRAWN
 * @param assignedAt    分配时间，Unix 毫秒，UTC
 * @param withdrawnAt   退组时间，Unix 毫秒，UTC；null 表示未退组
 */
public record AllocationView(
        String experimentId,
        String participantId,
        String blindCode,
        Integer blockNo,
        String centerId,
        Integer versionNo,
        String status,
        long assignedAt,
        Long withdrawnAt
) {
}
