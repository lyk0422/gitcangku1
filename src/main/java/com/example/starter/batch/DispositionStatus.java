package com.example.starter.batch;

/**
 * 召回处置单状态：
 * SUBMITTED 质量负责人已提交、待生产负责人二审；
 * CONFIRMED 生产负责人确认成功，全部分类已落账（终态）；
 * REJECTED 生产负责人拒绝，批次不变（终态）；
 * CANCELLED 确认/拒绝前取消，批次不变（终态）。
 */
public enum DispositionStatus {
    SUBMITTED,
    CONFIRMED,
    REJECTED,
    CANCELLED
}
