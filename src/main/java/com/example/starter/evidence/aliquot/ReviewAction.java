package com.example.starter.evidence.aliquot;

/**
 * 联合取样审核动作，只追加于审核历史。
 * CONFIRM 审核确认（seq=1/2 按顺序）；REJECT 审核人拒绝并释放全部预留；
 * CANCEL 审核前申请保管人取消并释放全部预留。
 */
public enum ReviewAction {
    CONFIRM,
    REJECT,
    CANCEL
}
