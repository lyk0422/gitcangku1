package com.example.starter.blind.dto;

/**
 * 区组容量与已用席位统计视图；不含处理映射。
 * 已用席位含已退组参与者（退组不释放席位）。
 *
 * @param experimentId   实验编号
 * @param version        当前实验版本号
 * @param status         OPEN / CLOSED
 * @param seatsPerBlock  每区组席位数，固定为 4
 * @param blockCount     当前区组总数
 * @param totalSeats     总席位数 = 区组总数 × 4
 * @param occupiedSeats  已占用席位数（含 WITHDRAWN 退组席位，不释放）
 * @param availableSeats 可登记空位数 = 总席位 - 已占用
 */
public record CapacityView(
        String experimentId,
        int version,
        String status,
        int seatsPerBlock,
        int blockCount,
        int totalSeats,
        int occupiedSeats,
        int availableSeats
) {
}
