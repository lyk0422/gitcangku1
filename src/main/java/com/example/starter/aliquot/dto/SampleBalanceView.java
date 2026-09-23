package com.example.starter.aliquot.dto;

/**
 * 母样数量余额视图（只读）。可用余额 = totalQuantity - reserved - consumed。
 *
 * @param sampleKey     母样业务键
 * @param totalQuantity 登记总量，不可修改
 * @param unit          计量单位
 * @param reserved      当前已预留未决数量
 * @param consumed      累计已耗用数量
 * @param available     当前可用余额（= 总量 - 预留 - 耗用）
 */
public record SampleBalanceView(
        String sampleKey,
        long totalQuantity,
        String unit,
        long reserved,
        long consumed,
        long available) {
}
