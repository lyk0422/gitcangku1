package com.example.starter.domain;

/**
 * 观测申请状态。
 */
public enum RequestStatus {

    /** 待批准或候补中：申请已受理，尚未锁定仪器窗口。 */
    PENDING,

    /** 已分配：整段窗口已锁定（管理员批准或候补接替）。 */
    ALLOCATED,

    /** 已撤销：研究组在窗口开始前主动撤销。 */
    CANCELLED
}
