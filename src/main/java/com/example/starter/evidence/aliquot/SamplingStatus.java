package com.example.starter.evidence.aliquot;

/**
 * 联合取样单状态。
 * PENDING 已原子预留、等待两名实验审核人按顺序确认；
 * CONFIRMED 两次确认完成，预留已一次转为耗用并生成 SEALED 子样（终态）；
 * REJECTED 审核人拒绝，全部预留一次释放（终态）；
 * CANCELLED 审核前由申请保管人取消，全部预留一次释放（终态）。
 */
public enum SamplingStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED
}
