package com.example.starter.evidence.aliquot.dto;

/**
 * 联合取样单明细只读视图。
 *
 * @param sampleKey     母样业务键
 * @param qty           从该母样取用数量
 * @param unit          取用时母样单位
 * @param sampleVersion 申请预留成功时的母样版本
 */
public record SamplingItemView(
        String sampleKey,
        long qty,
        String unit,
        long sampleVersion) {
}
