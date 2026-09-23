package com.example.starter.evidence.aliquot;

import java.time.LocalDateTime;

/**
 * 母样登记实体，对应 mother_sample 表。母样首次参与联合取样时登记，总量与单位不可修改。
 * 数量恒等式：totalQty = 剩余可用余额 + reservedQty + consumedQty。
 *
 * @param id           主键
 * @param sampleKey    母样业务键，关联 evidence.evidence_key，全局唯一
 * @param totalQty     登记总量，正整数，登记后不可修改
 * @param unit         数量单位，登记后不可修改
 * @param reservedQty  已被 PENDING 取样单预留、尚未耗用或释放的数量
 * @param consumedQty  二次确认成功后累计耗用数量
 * @param version      母样版本号，预留/释放/耗用每次变更加 1
 * @param createdAt    登记时间（Asia/Shanghai）
 * @param updatedAt    最近预留/释放/耗用变更时间（Asia/Shanghai）
 */
public record MotherSample(
        Long id,
        String sampleKey,
        long totalQty,
        String unit,
        long reservedQty,
        long consumedQty,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * 剩余可用余额：总量 - 预留 - 耗用。
     */
    public long availableQty() {
        return totalQty - reservedQty - consumedQty;
    }
}
