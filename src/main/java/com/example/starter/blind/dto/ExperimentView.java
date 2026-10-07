package com.example.starter.blind.dto;

/**
 * 实验视图；不含任何处理映射信息，MINIMIZE 模式也不含边际格数与失衡分数。
 *
 * @param experimentId  实验编号
 * @param blockCount    区组数量：BLOCK 为 2~8；MINIMIZE 统一为 1
 * @param seatsPerBlock 每区组席位数：BLOCK 固定为 4；MINIMIZE 等于总容量
 * @param totalSeats    总席位数：BLOCK 为区组数 × 4；MINIMIZE 为创建时给定的偶数容量
 * @param status        OPEN / CLOSED
 * @param createdAt     创建时间，Unix 毫秒，UTC
 * @param mode          分配模式：BLOCK / MINIMIZE
 */
public record ExperimentView(
        String experimentId,
        int blockCount,
        int seatsPerBlock,
        int totalSeats,
        String status,
        long createdAt,
        String mode
) {
}
