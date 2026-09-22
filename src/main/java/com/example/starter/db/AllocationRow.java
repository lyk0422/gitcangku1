package com.example.starter.db;

import java.time.Instant;

/**
 * allocation 表行：参与者席位领取记录。
 *
 * @param id            分配主键
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param blockNo       区组号
 * @param seatNo        区内席位序号（不对普通查询暴露）
 * @param blindCode     随机无含义盲码
 * @param status        分配状态 ENROLLED/WITHDRAWN
 * @param createdAt     领取时间（UTC）
 * @param updatedAt     最近状态变更时间（UTC）
 */
public record AllocationRow(
        long id,
        String experimentId,
        String participantId,
        int blockNo,
        int seatNo,
        String blindCode,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
