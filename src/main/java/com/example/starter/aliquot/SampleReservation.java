package com.example.starter.aliquot;

/**
 * 母样数量聚合只读投影（不对应独立表）：由取样单状态与明细实时聚合得到。
 * 预留 = 状态为 RESERVED 的明细数量之和；耗用 = 状态为 CONSUMED 的明细数量之和。
 *
 * @param sampleKey 母样业务键
 * @param reserved  当前已预留未决数量（非负）
 * @param consumed  累计已耗用数量（非负）
 */
public record SampleReservation(
        String sampleKey,
        long reserved,
        long consumed) {
}
