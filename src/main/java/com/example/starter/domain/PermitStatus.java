package com.example.starter.domain;

/** 豁免包状态。 */
public enum PermitStatus {
    /** 有效：可参与飞行审核核销。 */
    ACTIVE,
    /** 已撤销：未使用余额作废，不能再核销；已发生的历史核销保留。 */
    REVOKED
}
