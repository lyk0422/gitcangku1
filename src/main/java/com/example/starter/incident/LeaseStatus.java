package com.example.starter.incident;

/**
 * 互助租约状态。
 * OPEN：未结清（仍有待归还数量；租约到期但未还完时仍保持 OPEN，到期不自动虚构归还）。
 * CLOSED：分次归还累计恰好等于征调总量后关闭，终态；关闭时不再次释放来源额度。
 */
public enum LeaseStatus {
    OPEN,
    CLOSED
}
