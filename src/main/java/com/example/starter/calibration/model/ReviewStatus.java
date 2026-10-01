package com.example.starter.calibration.model;

/**
 * 复核记录状态。
 */
public enum ReviewStatus {

    /** 有效：引用的测量修订版本与证书版本均为当前值且证书有效，计入裁决法定人数。 */
    VALID,

    /** 已过期：提交时测量修订版本已变更或证书版本不符/已失效，不计入裁决法定人数。 */
    STALE
}