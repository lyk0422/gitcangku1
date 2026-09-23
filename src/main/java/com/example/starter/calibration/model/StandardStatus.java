package com.example.starter.calibration.model;

/**
 * 标准器版本状态。
 */
public enum StandardStatus {

    /** 有效：可在测量提交时被绑定。 */
    VALID,

    /** 已失效：经失效单激活后标记，不得再被新测量绑定。 */
    INVALID
}
