package com.example.starter.domain;

/**
 * 幂等调用记录区分的操作类型。
 */
public enum OperationType {

    /** 窗口申请。 */
    APPLY,

    /** 管理员批准（含与已分配窗口冲突的裁决）。 */
    APPROVE,

    /** 研究组撤销（含候补接替）。 */
    CANCEL
}
