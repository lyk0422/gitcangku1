package com.example.starter.blind.dto;

/**
 * 实验视图；不含任何处理映射信息。
 *
 * @param experimentId 实验编号
 * @param blockCount   区组数量（创建 2~8，扩容后最多 16）
 * @param version      实验版本号，创建为 1，每次成功扩容加一
 * @param seatsPerBlock 每区组席位数，固定为 4
 * @param totalSeats   总席位数，固定为区组数 × 4
 * @param status       OPEN / CLOSED
 * @param createdAt    创建时间，Unix 毫秒，UTC
 */
public record ExperimentView(
        String experimentId,
        int blockCount,
        int version,
        int seatsPerBlock,
        int totalSeats,
        String status,
        long createdAt
) {
}
