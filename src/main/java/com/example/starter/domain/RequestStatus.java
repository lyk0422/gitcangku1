package com.example.starter.domain;

/**
 * 观测申请状态。
 */
public enum RequestStatus {

    /** 候补：申请已受理，尚未被批准或接替空档。 */
    PENDING,
    /** 已分配：管理员批准并锁定整段窗口。 */
    ALLOCATED,
    /** 已撤销：研究组撤销（含候补撤销与已分配窗口撤销）。 */
    CANCELLED,
    /** 已接替：候补申请完整落入某次撤销释放的空档并被分配。 */
    PROMOTED
}
