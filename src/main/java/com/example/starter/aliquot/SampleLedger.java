package com.example.starter.aliquot;

import java.time.LocalDateTime;

/**
 * 母样台账实体，对应 sample_ledger 表。
 * 母样首次参与联合取样前登记；sampleKey/totalQuantity/unit 登记后不可修改。
 *
 * @param id            主键
 * @param sampleKey     母样业务键，与证物键相同，全局唯一
 * @param totalQuantity 登记总量，正整数，不可修改
 * @param unit          计量单位，不可修改
 * @param createdAt     登记时间（Asia/Shanghai）
 */
public record SampleLedger(
        Long id,
        String sampleKey,
        long totalQuantity,
        String unit,
        LocalDateTime createdAt) {
}
