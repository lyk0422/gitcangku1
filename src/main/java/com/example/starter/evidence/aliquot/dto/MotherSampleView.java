package com.example.starter.evidence.aliquot.dto;

/**
 * 母样余额只读视图：返回总量、单位、预留、耗用与剩余可用余额及当前版本。
 *
 * @param sampleKey    母样业务键
 * @param totalQty     登记总量（不可修改）
 * @param unit         数量单位（不可修改）
 * @param reservedQty  当前被 PENDING 取样单预留的数量
 * @param consumedQty  累计已耗用数量
 * @param availableQty 剩余可用余额 = 总量 - 预留 - 耗用
 * @param version      母样当前版本号
 */
public record MotherSampleView(
        String sampleKey,
        long totalQty,
        String unit,
        long reservedQty,
        long consumedQty,
        long availableQty,
        long version) {
}
