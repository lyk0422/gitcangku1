package com.example.starter.calibration.model;

/**
 * 复核记录状态。
 */
public enum ReviewStatus {

    /** 有效：引用的测量修订版本与证书版本均为当前版本且证书未失效。 */
    VALID,

    /** 已过期：引用的版本已变更或证书已失效，复核不计入裁决。 */
    STALE
}