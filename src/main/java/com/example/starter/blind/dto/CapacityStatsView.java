package com.example.starter.blind.dto;

/**
 * 区组容量与已用席位统计视图；仅统计数量，不含任何处理映射。
 * 退组席位仍计为已占用（不释放、不回填）。
 *
 * @param experimentId  实验编号
 * @param version       实验版本
 * @param status        OPEN / CLOSED
 * @param blockCount    当前区组总数
 * @param seatsPerBlock 每区组席位数，固定 4
 * @param totalSeats    总席位数 = 区组总数 × 4
 * @param occupiedSeats 已占用席位数（含已退组席位，退组不释放）
 * @param withdrawnSeats 其中已退组席位数
 * @param vacantSeats   尚可登记的空位数 = totalSeats - occupiedSeats
 */
public record CapacityStatsView(
        String experimentId,
        int version,
        String status,
        int blockCount,
        int seatsPerBlock,
        int totalSeats,
        int occupiedSeats,
        int withdrawnSeats,
        int vacantSeats
) {
}
