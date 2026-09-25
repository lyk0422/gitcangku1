package com.example.starter.calibration.model;

/**
 * 同行复核记录状态。复核记录不可变，状态在写入时固化。
 */
public enum ReviewState {

    /** 有效：针对测量当前版本写入，可参与该版本的放行门禁判定。 */
    VALID,

    /** 失效：复核提交时测量当前版本已变化（已被修订），不得用于放行，仅作历史保留。 */
    STALE
}
