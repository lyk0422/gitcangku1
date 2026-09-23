package com.example.starter.domain;

/**
 * 豁免包状态：签发后不可修改，仅可撤销未使用余额。
 */
public enum PermitStatus {
    /** 已签发：项可在额度与 UTC 有效区间内参与核销。 */
    ISSUED,
    /** 已撤销：整包失效，未使用余额不可再核销；历史核销不回写。 */
    REVOKED
}
