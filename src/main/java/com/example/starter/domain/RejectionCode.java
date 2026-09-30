package com.example.starter.domain;

/**
 * 机器可区分的拒绝原因编码。
 */
public enum RejectionCode {

    /** 当前潮高版本下可用深度不足（吃水+安全余量超过可用深度）。 */
    DEPTH_INSUFFICIENT,
    /** 同一泊位时段已被其他已确认靠泊占用。 */
    BERTH_OCCUPIED,
    /** 潮高修订后重新校核，可用深度不足。 */
    TIDE_REVISION_DEPTH_INSUFFICIENT,
    /** 人工复核拒绝。 */
    MANUAL_REJECT
}
