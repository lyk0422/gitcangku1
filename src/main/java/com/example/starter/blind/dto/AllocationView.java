package com.example.starter.blind.dto;

/**
 * 分配/退组普通视图：只含无含义盲码、区组号、参与者编号、归属中心/协议版本与在组状态。
 * 严禁包含处理代码 treatment 或可直接解码的席位号 seatNo。
 *
 * @param experimentId    实验编号
 * @param participantId   合成参与者编号
 * @param centerId        登记中心编号；null 表示旧的无中心全局登记入口
 * @param protocolVersion 登记时生效的协议版本号，该受试者永远归属此版本
 * @param blindCode       随机无含义盲码
 * @param blockNo         区组号（所属协议版本内，不含区组内席位序号）
 * @param status          ASSIGNED / WITHDRAWN
 * @param assignedAt      分配时间，Unix 毫秒，UTC
 * @param withdrawnAt     退组时间，Unix 毫秒，UTC；null 表示未退组
 */
public record AllocationView(
        String experimentId,
        String participantId,
        String centerId,
        int protocolVersion,
        String blindCode,
        int blockNo,
        String status,
        long assignedAt,
        Long withdrawnAt
) {
}
