package com.example.starter.calibration.model;

/**
 * 复核记录状态（记录本身不可变，状态在写入时固化）。
 */
public enum ReviewStatus {

    /** 提交时针对当时当前修订版本；是否仍有效由测量当前版本号判定。 */
    VALID,

    /** 提交时被复核版本已过期（版本变化），不得用于放行，仅保留历史。 */
    STALE
}
