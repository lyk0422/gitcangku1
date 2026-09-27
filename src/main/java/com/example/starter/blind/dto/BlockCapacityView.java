package com.example.starter.blind.dto;

/**
 * 区组可用名额诊断视图：按封存数量重算后的实时统计，只读。
 *
 * @param blockNo        区组号
 * @param totalSeats     该区组现有席位总数（退役后扩容的区组可能少于 4）
 * @param occupiedSeats  已分配席位数（含已退组，席位不释放）
 * @param sealedSeats    已封存席位数
 * @param availableSeats 仍可分配的席位数（ACTIVE 且未分配）
 */
public record BlockCapacityView(
        int blockNo,
        int totalSeats,
        int occupiedSeats,
        int sealedSeats,
        int availableSeats
) {
}
