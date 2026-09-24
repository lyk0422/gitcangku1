package com.example.starter.evidence.destruction;

/**
 * 销毁令审批决定。AGREED 同意（每名审批人至多一次，两人互不相同）；
 * REJECTED 拒绝（任一审批人拒绝立即终结销毁令，原因不可改写）。
 */
public enum ApprovalDecision {
    AGREED,
    REJECTED
}
