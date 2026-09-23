package com.example.starter.batch;

/**
 * 召回处置单状态：
 * SUBMITTED 质量负责人已提交、等待不同的生产负责人二审；
 * CONFIRMED 二审通过，闭包批次已在同一事务内按分类落账（终态）；
 * REJECTED 生产负责人二审拒绝，批次不发生任何变化（终态）；
 * CANCELLED 确认前由提交人取消，批次不发生任何变化（终态）。
 */
public enum DispositionStatus {
    SUBMITTED,
    CONFIRMED,
    REJECTED,
    CANCELLED
}
