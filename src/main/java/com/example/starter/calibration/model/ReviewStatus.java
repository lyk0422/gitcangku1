package com.example.starter.calibration.model;

/**
 * 复核状态。
 */
public enum ReviewStatus {

    /** 有效：计入法定人数。 */
    VALID,

    /** 已失效：复核引用的测量修订版本已变更或证书已失效，不计入法定人数。 */
    STALE
}