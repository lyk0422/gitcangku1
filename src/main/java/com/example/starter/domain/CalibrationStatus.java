package com.example.starter.domain;

/**
 * 校准记录状态：ACTIVE 有效可用；VOIDED 经复核作废，不得再用于新绑定。
 */
public enum CalibrationStatus {
    ACTIVE,
    VOIDED
}