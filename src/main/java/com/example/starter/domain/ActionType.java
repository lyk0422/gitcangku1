package com.example.starter.domain;

/**
 * 幂等调用记录中的业务动作类型。
 */
public enum ActionType {

    /** 窗口申请。 */
    APPLY,
    /** 管理员批准。 */
    APPROVE,
    /** 研究组撤销。 */
    CANCEL
}
