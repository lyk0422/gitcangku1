package com.example.starter.aliquot;

/**
 * 联合取样单状态。
 * RESERVED 已原子预留、等待两次审核；CONSUMED 两次确认完成、预留转为耗用（终态）；
 * REJECTED 审核拒绝（终态，预留已释放）；CANCELLED 审核前取消（终态，预留已释放）。
 */
public enum AliquotStatus {
    RESERVED,
    CONSUMED,
    REJECTED,
    CANCELLED
}
